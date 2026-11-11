package cn.gugupan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.gugupan.Models.InboxRequest;
import cn.gugupan.Models.SettingsRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.*;
import java.security.MessageDigest;
import java.util.zip.ZipInputStream;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransferIntegrationTest {
    private static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("gugupan-test-"); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:gugupan-tests;DB_CLOSE_DELAY=-1");
        registry.add("gugupan.data-dir", () -> DATA.toString());
        registry.add("gugupan.initial-password", () -> "TestPassword123!");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PanService service;
    @Autowired ChunkService chunks;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws IOException {
        jdbc.update("UPDATE upload_sessions SET expires_at = 0");
        service.cleanup();
        for (var file : service.files()) service.deleteFile(file.id());
        jdbc.update("DELETE FROM transfer_links");
        service.updateSettings(new SettingsRequest(1024, 512, 7));
    }

    @Test
    void adminLoginAndCsrfAreEnforced() throws Exception {
        mvc.perform(get("/api/admin/files")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").param("username", "admin").param("password", "TestPassword123!"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "admin").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "admin").param("password", "TestPassword123!"))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/admin/files").session((MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isOk());
    }

    @Test
    void passwordProtectedShareChecksMembershipAndRevocation() throws Exception {
        JsonNode file = upload("hello.txt", "hello");
        JsonNode other = upload("other.txt", "other");
        String body = mapper.writeValueAsString(Map.of("fileIds", new String[] { file.get("id").asText() },
                "title", "测试分享", "days", 3, "password", "123456"));
        JsonNode share = json(mvc.perform(post("/api/admin/shares").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String base = "/api/public/links/" + share.get("token").asText();
        String download = base + "/files/" + file.get("id").asText();
        mvc.perform(get(base)).andExpect(jsonPath("$.locked").value(true)).andExpect(jsonPath("$.files").isEmpty());
        mvc.perform(get(download)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/unlock").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(base + "/unlock").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"123456\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(download).session(session)).andExpect(status().isOk()).andExpect(content().string("hello"))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
        mvc.perform(get(base + "/files/" + other.get("id").asText()).session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/resolve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("code", share.get("code").asText().toLowerCase()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.path").value("/s/" + share.get("token").asText()));
        mvc.perform(delete("/api/admin/links/" + share.get("token").asText()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(download).session(session)).andExpect(status().isGone());
    }

    @Test
    void inboxReceiptsDoNotExposeFilesAndLimitsAreEnforced() throws Exception {
        var inbox = service.createInbox(new InboxRequest("收件", "", "", 1, 5, 2, 4));
        String base = "/api/public/links/" + inbox.token();
        mvc.perform(post(base + "/files").with(csrf()).param("name", "large.bin")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[5])).andExpect(status().isPayloadTooLarge());
        for (int index = 0; index < 2; index++) {
            mvc.perform(post(base + "/files").with(csrf()).param("name", "收到的.txt")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM).content("ok"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true)).andExpect(jsonPath("$.id").doesNotExist());
        }
        mvc.perform(get(base)).andExpect(jsonPath("$.files").isEmpty()).andExpect(jsonPath("$.usage.fileCount").value(2));
        mvc.perform(get("/api/admin/files")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/files").with(csrf()).param("name", "third.bin")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content("x")).andExpect(status().isPayloadTooLarge());
        assertThat(service.files()).hasSize(2);
    }

    @Test
    void expiredFileStopsDownloadingBeforePhysicalCleanup() throws Exception {
        JsonNode file = upload("expired.txt", "expired");
        String id = file.get("id").asText();
        jdbc.update("UPDATE stored_files SET expires_at = ? WHERE id = ?", System.currentTimeMillis() - 1, id);
        mvc.perform(get("/api/admin/files/" + id + "/download").with(user("admin").roles("ADMIN")))
                .andExpect(status().isGone());
        assertThat(Files.exists(service.filePath(id))).isTrue();
        service.cleanup();
        assertThat(Files.exists(service.filePath(id))).isFalse();
        assertThat(service.files()).isEmpty();
    }

    @Test
    void failedAndOversizedStreamsReleaseReservedQuota() throws Exception {
        service.updateSettings(new SettingsRequest(4, 4, 7));
        assertThatThrownBy(() -> service.upload(new ByteArrayInputStream(new byte[5]), "bad.bin", 4, 7, null))
                .isInstanceOf(ApiException.class);
        assertThat(service.stats().get("reservedBytes")).isEqualTo(0L);
        assertThat(service.files()).isEmpty();
        try (var temporaryFiles = Files.list(DATA.resolve("tmp"))) { assertThat(temporaryFiles.count()).isZero(); }
        service.upload(new ByteArrayInputStream(new byte[4]), "good.bin", 4, 7, null);
        assertThat(service.files()).hasSize(1);
    }

    @Test
    void concurrentUploadsCannotReserveTheSameQuota() throws Exception {
        service.updateSettings(new SettingsRequest(4, 4, 7));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> {
                try {
                    service.upload(new InputStream() {
                        @Override public int read() throws IOException {
                            started.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException(exception); }
                            throw new IOException("simulated disconnect");
                        }
                    }, "first.bin", 4, 7, null);
                } catch (IOException expected) { /* Simulated disconnect must release the reservation. */ }
            });
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.upload(new ByteArrayInputStream(new byte[1]), "second.bin", 1, 7, null))
                        .isInstanceOf(ApiException.class).hasMessage("存储空间不足");
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(service.stats().get("reservedBytes")).isEqualTo(0L);
        service.upload(new ByteArrayInputStream(new byte[4]), "retry.bin", 4, 7, null);
        assertThat(service.files()).hasSize(1);
    }

    @Test
    void chunkUploadResumesVerifiesAndCompletesIdempotently() throws Exception {
        service.updateSettings(new SettingsRequest(16 * 1024 * 1024, 8 * 1024 * 1024, 7));
        byte[] bytes = new byte[ChunkService.CHUNK_SIZE + 17];
        new Random(42).nextBytes(bytes);
        String id = UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(Map.of("id", id, "name", "分片.bin", "size", bytes.length, "sha256", hash(bytes)));
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(post("/api/admin/uploads").with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        assertThat(service.stats().get("reservedBytes")).isEqualTo((long) bytes.length);
        byte[] first = Arrays.copyOf(bytes, ChunkService.CHUNK_SIZE);
        String base = "/api/admin/uploads/" + id;
        mvc.perform(put(base + "/parts/0").with(user("admin").roles("ADMIN")).with(csrf())
                .header("X-Chunk-SHA256", "0".repeat(64)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(first))
                .andExpect(status().isBadRequest());
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(put(base + "/parts/0").with(user("admin").roles("ADMIN")).with(csrf())
                    .header("X-Chunk-SHA256", hash(first)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(first))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(base).with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.uploaded.length()").value(1)).andExpect(jsonPath("$.uploaded[0]").value(0));
        mvc.perform(post(base + "/complete").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isConflict());
        byte[] last = Arrays.copyOfRange(bytes, ChunkService.CHUNK_SIZE, bytes.length);
        mvc.perform(put(base + "/parts/1").with(user("admin").roles("ADMIN")).with(csrf())
                .header("X-Chunk-SHA256", hash(last)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(last))
                .andExpect(status().isOk());
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(post(base + "/complete").with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));
        }
        assertThat(service.files()).hasSize(1);
        assertThat(Files.readAllBytes(service.filePath(id))).isEqualTo(bytes);
        assertThat(service.stats().get("reservedBytes")).isEqualTo(0L);
        chunks.cancel(id, null);
        assertThat(service.availableFile(id).size()).isEqualTo(bytes.length);
        // A client-provided upload ID must never overwrite an existing stored file.
        mvc.perform(post("/api/admin/uploads").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(Files.readAllBytes(service.filePath(id))).isEqualTo(bytes);
    }

    @Test
    void inboxChunksEnforcePasswordOwnershipRevocationAndCleanup() throws Exception {
        var inbox = service.createInbox(new InboxRequest("分片收件", "", "1234", 1, 10, 2, 10));
        var other = service.createInbox(new InboxRequest("另一收件箱", "", "", 1, 10, 2, 10));
        MockHttpSession session = new MockHttpSession();
        String base = "/api/public/links/" + inbox.token();
        String id = UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(Map.of("id", id, "name", "test.bin", "size", 3, "sha256", hash(new byte[3])));
        mvc.perform(post(base + "/uploads").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(base + "/unlock").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"1234\"}")).andExpect(status().isOk());
        mvc.perform(post(base + "/uploads").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/links/" + other.token() + "/uploads/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/uploads/" + id)).andExpect(status().isUnauthorized());
        service.revokeLink(inbox.token());
        mvc.perform(put(base + "/uploads/" + id + "/parts/0").session(session).with(csrf())
                .header("X-Chunk-SHA256", hash(new byte[3])).contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3]))
                .andExpect(status().isGone());
        jdbc.update("UPDATE upload_sessions SET expires_at = 0 WHERE id = ?", id);
        service.cleanup();
        assertThat(service.stats().get("reservedBytes")).isEqualTo(0L);
        assertThat(Files.exists(DATA.resolve("uploads").resolve(id))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_sessions", Integer.class)).isZero();
    }

    @Test
    void chunkFinalHashAndCrashRecoveryAndEmptyFileAreHandled() throws Exception {
        String id = UUID.randomUUID().toString();
        chunks.create(new Models.UploadRequest(id, "hash.bin", 3, hash(new byte[3]), 7), null);
        byte[] wrong = {1, 2, 3};
        chunks.part(id, null, 0, hash(wrong), new ByteArrayInputStream(wrong));
        assertThatThrownBy(() -> chunks.finish(id, null)).isInstanceOf(ApiException.class).hasMessageContaining("校验失败");
        chunks.part(id, null, 0, hash(new byte[3]), new ByteArrayInputStream(new byte[3]));
        // Simulate interruption between the atomic move and database commit.
        Files.move(DATA.resolve("uploads").resolve(id), service.filePath(id));
        service.cleanup();
        assertThat(Files.exists(service.filePath(id))).isTrue();
        assertThat(chunks.finish(id, null).size()).isEqualTo(3);
        String empty = UUID.randomUUID().toString();
        chunks.create(new Models.UploadRequest(empty, "empty.txt", 0, hash(new byte[0]), 7), null);
        assertThat(chunks.finish(empty, null).size()).isZero();
    }

    @Test
    void textTransferProtectsContentAndSupportsCodeAndUtf8Download() throws Exception {
        String text = "你好，咕咕盘\n<script>alert('text')</script>\n第二行 😀";
        var response = mvc.perform(post("/api/admin/texts").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "title", "文本测试", "content", text, "password", "1234", "days", 3))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("TEXT")).andReturn();
        JsonNode link = json(response.getResponse().getContentAsString());
        String token = link.get("token").asText();
        String base = "/api/public/links/" + token;
        mvc.perform(get(base)).andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.text").doesNotExist()).andExpect(jsonPath("$.files").isEmpty());
        String download = base + "/files/" + service.shareFiles(token).getFirst().id();
        mvc.perform(get(download)).andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(base + "/unlock").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"1234\"}")).andExpect(status().isOk());
        mvc.perform(get(base).session(session)).andExpect(jsonPath("$.text").value(text));
        mvc.perform(get(download).session(session)).andExpect(content().bytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(post("/api/public/resolve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("code", link.get("code").asText()))))
                .andExpect(jsonPath("$.path").value("/s/" + token));
        service.revokeLink(token);
        mvc.perform(get(base).session(session)).andExpect(status().isGone());
        assertThat(service.stats().get("usedBytes")).isEqualTo((long) text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void archiveStreamsUniqueNamesAndEnforcesPublicAccess() throws Exception {
        String first = upload("报告.txt", "first").get("id").asText();
        String second = upload("报告.txt", "second").get("id").asText();
        var async = mvc.perform(get("/api/admin/files/archive").param("ids", first + "," + second)
                .with(user("admin").roles("ADMIN"))).andExpect(request().asyncStarted()).andReturn();
        byte[] archive = mvc.perform(asyncDispatch(async)).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), java.nio.charset.StandardCharsets.UTF_8)) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("报告.txt");
            assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("first");
            assertThat(zip.getNextEntry().getName()).isEqualTo("(2) 报告.txt");
            assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("second");
            assertThat(zip.getNextEntry()).isNull();
        }
        var share = service.createShare(new Models.ShareRequest(List.of(first, second), "ZIP", "", "1234", 3));
        String base = "/api/public/links/" + share.token();
        mvc.perform(get(base + "/archive")).andExpect(status().isForbidden());
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post(base + "/unlock").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"1234\"}")).andExpect(status().isOk());
        var publicZip = mvc.perform(get(base + "/archive").session(session)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(publicZip)).andExpect(status().isOk());
        service.revokeLink(share.token());
        mvc.perform(get(base + "/archive").session(session)).andExpect(status().isGone());
        mvc.perform(get("/api/admin/files/archive").param("ids", "missing").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private JsonNode upload(String name, String content) throws Exception {
        return json(mvc.perform(post("/api/admin/files").with(user("admin").roles("ADMIN")).with(csrf())
                .param("name", name).contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String text) throws Exception { return mapper.readTree(text); }
}
