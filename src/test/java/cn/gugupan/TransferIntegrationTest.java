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
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws IOException {
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

    private JsonNode upload(String name, String content) throws Exception {
        return json(mvc.perform(post("/api/admin/files").with(user("admin").roles("ADMIN")).with(csrf())
                .param("name", name).contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String text) throws Exception { return mapper.readTree(text); }
}
