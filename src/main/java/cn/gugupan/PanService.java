package cn.gugupan;

import static cn.gugupan.Models.*;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PanService {
    private static final Logger LOG = LoggerFactory.getLogger(PanService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String FILE_COLUMNS = "id, original_name, size_bytes, inbox_token, expires_at, created_at";
    private static final String LINK_COLUMNS = "token, pickup_code, kind, title, note, password_hash, expires_at, revoked, quota_bytes, max_files, max_file_bytes, created_at";
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    private static final RowMapper<FileItem> FILE_MAPPER = (row, index) -> new FileItem(
            row.getString("id"), row.getString("original_name"), row.getLong("size_bytes"),
            row.getString("inbox_token"), row.getLong("expires_at"), row.getLong("created_at"));
    private static final RowMapper<LinkItem> LINK_MAPPER = (row, index) -> mapLink(row);

    private record Reservation(long bytes, String inboxToken) {}
    private final Object quotaLock = new Object();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final PanProperties properties;
    private final Path root;

    public PanService(JdbcTemplate jdbc, TransactionTemplate transactions,
                      PasswordEncoder passwords, PanProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.passwords = passwords;
        this.properties = properties;
        this.root = properties.dataDir().toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(root.resolve("files"));
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("uploads"));
        jdbc.query("SELECT id, size_bytes, inbox_token FROM upload_sessions WHERE finished = FALSE", row -> {
            reservations.put(row.getString("id"), new Reservation(row.getLong("size_bytes"), row.getString("inbox_token")));
        });
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_settings", Integer.class) == 0) {
            String password = properties.initialPassword();
            boolean generated = password == null || password.isBlank();
            if (generated) {
                password = randomToken();
            }
            validatePassword(password);
            String username = properties.initialUsername();
            if (username == null || username.isBlank() || username.length() > 64) {
                throw new IllegalArgumentException("管理员用户名长度必须为 1～64 个字符");
            }
            // Write the bootstrap credential before committing the account, so it is recoverable on restart.
            if (generated) {
                Path credential = root.resolve("initial-admin-password.txt");
                if (Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                    if (!Files.exists(credential)) {
                        Files.createFile(credential, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    }
                }
                Files.writeString(credential, password, StandardCharsets.UTF_8);
                LOG.info("首次启动的管理员密码已写入 {}，请登录后修改密码", credential);
            }
            jdbc.update("INSERT INTO app_settings (id, username, password_hash, quota_bytes, max_file_bytes, retention_days) VALUES (1, ?, ?, ?, ?, 7)",
                    username, passwords.encode(password), 20 * GIB, GIB);
        }
        // No request can be in flight during startup. Leftover partial uploads can be removed safely.
        try (var files = Files.list(root.resolve("tmp"))) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    public Settings settings() {
        return jdbc.queryForObject("SELECT username, quota_bytes, max_file_bytes, retention_days FROM app_settings WHERE id = 1",
                (row, index) -> new Settings(row.getString(1), row.getLong(2), row.getLong(3), row.getInt(4)));
    }

    public String adminPasswordHash() {
        return jdbc.queryForObject("SELECT password_hash FROM app_settings WHERE id = 1", String.class);
    }

    public Map<String, Object> stats() {
        synchronized (quotaLock) {
            return Map.of("usedBytes", usedBytes(null), "reservedBytes", reservedBytes(null),
                    "fileCount", jdbc.queryForObject("SELECT COUNT(*) FROM stored_files", Long.class),
                    "settings", settings());
        }
    }

    public Settings updateSettings(SettingsRequest request) {
        synchronized (quotaLock) {
            if (request.maxFileBytes() > request.quotaBytes()) {
                throw new ApiException(400, "单文件上限不能大于总容量");
            }
            if (request.quotaBytes() < usedBytes(null) + reservedBytes(null)) {
                throw new ApiException(400, "总容量不能小于已用空间和正在上传的文件");
            }
            jdbc.update("UPDATE app_settings SET quota_bytes = ?, max_file_bytes = ?, retention_days = ? WHERE id = 1",
                    request.quotaBytes(), request.maxFileBytes(), request.retentionDays());
            return settings();
        }
    }

    public void changePassword(ChangePasswordRequest request) throws IOException {
        validatePassword(request.newPassword());
        synchronized (quotaLock) {
            if (!passwords.matches(request.currentPassword(), adminPasswordHash())) {
                throw new ApiException(400, "当前密码不正确");
            }
            Files.deleteIfExists(root.resolve("initial-admin-password.txt"));
            jdbc.update("UPDATE app_settings SET password_hash = ? WHERE id = 1", passwords.encode(request.newPassword()));
        }
    }

    public List<FileItem> files() {
        return jdbc.query("SELECT " + FILE_COLUMNS + " FROM stored_files ORDER BY created_at DESC", FILE_MAPPER);
    }

    public FileItem file(String id) {
        return jdbc.query("SELECT " + FILE_COLUMNS + " FROM stored_files WHERE id = ?", FILE_MAPPER, id)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "文件不存在或已删除"));
    }

    public FileItem availableFile(String id) {
        FileItem file = file(id);
        if (file.expiresAt() <= System.currentTimeMillis()) {
            throw new ApiException(410, "文件已过期");
        }
        if (!Files.isRegularFile(filePath(id))) {
            throw new ApiException(404, "文件内容不存在");
        }
        return file;
    }

    public Path filePath(String id) {
        // Storage paths use canonical UUIDs, never the original filename.
        try {
            return root.resolve("files").resolve(UUID.fromString(id).toString());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(404, "文件不存在");
        }
    }

    public FileItem upload(InputStream input, String originalName, long contentLength,
                           Integer retentionDays, String inboxToken) throws IOException {
        String name = cleanFilename(originalName);
        String id = UUID.randomUUID().toString();
        long reserved;
        int days;
        days = reserveUpload(id, contentLength, retentionDays, inboxToken);
        synchronized (quotaLock) { reserved = reservations.get(id).bytes(); }
        Path temporary = root.resolve("tmp").resolve(id);
        Path destination = filePath(id);
        boolean committed = false;
        try {
            long size = 0;
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > reserved) {
                        throw new ApiException(413, "上传内容超过允许大小");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (contentLength >= 0 && size != contentLength) {
                throw new ApiException(400, "上传未完成，请重新上传");
            }
            synchronized (quotaLock) {
                if (inboxToken != null) {
                    activeLink(inboxToken, "INBOX");
                }
                long now = System.currentTimeMillis();
                FileItem result = new FileItem(id, name, size, inboxToken, now + days * DAY_MS, now);
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                transactions.executeWithoutResult(status -> jdbc.update(
                        "INSERT INTO stored_files (id, original_name, size_bytes, inbox_token, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        result.id(), result.name(), result.size(), result.inboxToken(), result.expiresAt(), result.createdAt()));
                committed = true;
                return result;
            }
        } finally {
            synchronized (quotaLock) {
                reservations.remove(id);
            }
            Files.deleteIfExists(temporary);
            if (!committed) {
                Files.deleteIfExists(destination);
            }
        }
    }

    Object quotaLock() { return quotaLock; }

    int reserveUpload(String id, long contentLength, Integer retentionDays, String inboxToken) {
        synchronized (quotaLock) {
            Settings settings = settings();
            int days = retentionDays == null ? settings.retentionDays() : retentionDays;
            if (days < 1 || days > 365) {
                throw new ApiException(400, "文件保留天数须为 1～365 天");
            }
            long max = settings.maxFileBytes();
            if (inboxToken != null) {
                LinkItem inbox = activeLink(inboxToken, "INBOX");
                max = Math.min(max, inbox.maxFileBytes());
                long pending = reservations.values().stream().filter(item -> inboxToken.equals(item.inboxToken())).count();
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM stored_files WHERE inbox_token = ?", Long.class, inboxToken);
                if (count + pending >= inbox.maxFiles()) {
                    throw new ApiException(413, "收件箱文件数量已达上限");
                }
            }
            long reserved = contentLength < 0 ? max : contentLength;
            if (reserved > max) {
                throw new ApiException(413, "文件超过单文件大小上限");
            }
            if (reserved > settings.quotaBytes() - usedBytes(null) - reservedBytes(null)) {
                throw new ApiException(413, "存储空间不足");
            }
            if (inboxToken != null) {
                LinkItem inbox = activeLink(inboxToken, "INBOX");
                if (reserved > inbox.quotaBytes() - usedBytes(inboxToken) - reservedBytes(inboxToken)) {
                    throw new ApiException(413, "收件箱剩余容量不足");
                }
            }
            if (reservations.size() >= 8) {
                throw new ApiException(429, "当前上传任务较多，请稍后再试");
            }
            reservations.put(id, new Reservation(reserved, inboxToken));
            return days;
        }
    }

    void releaseUpload(String id) { reservations.remove(id); }

    FileItem finishUpload(String id, String name, long size, int days, String inboxToken, Path staging) throws IOException {
        synchronized (quotaLock) {
            if (inboxToken != null) activeLink(inboxToken, "INBOX");
            long now = System.currentTimeMillis();
            FileItem result = new FileItem(id, name, size, inboxToken, now + days * DAY_MS, now);
            Path destination = filePath(id);
            // A previous process may have moved the bytes just before its transaction was rolled back.
            if (Files.exists(staging)) Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO stored_files (id, original_name, size_bytes, inbox_token, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        id, name, size, inboxToken, result.expiresAt(), now);
                jdbc.update("UPDATE upload_sessions SET finished = TRUE WHERE id = ?", id);
            });
            reservations.remove(id);
            return result;
        }
    }

    public LinkItem createText(TextRequest request) throws IOException {
        synchronized (quotaLock) {
            byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
            FileItem file = upload(new java.io.ByteArrayInputStream(content), "文本中转.txt", content.length, request.days(), null);
            try {
                return transactions.execute(status -> {
                    LinkItem link = insertLink("TEXT", request.title(), "", request.password(), request.days(), 0, 0, 0);
                    jdbc.update("INSERT INTO share_files (link_token, file_id) VALUES (?, ?)", link.token(), file.id());
                    return link;
                });
            } catch (RuntimeException exception) {
                deleteFile(file.id());
                throw exception;
            }
        }
    }

    public void deleteFile(String id) throws IOException {
        synchronized (quotaLock) {
            file(id);
            // Removing bytes first keeps quota accounting conservative if deletion fails.
            Files.deleteIfExists(filePath(id));
            jdbc.update("DELETE FROM stored_files WHERE id = ?", id);
        }
    }

    public FileItem extendFile(String id, int days) {
        synchronized (quotaLock) {
            availableFile(id);
            jdbc.update("UPDATE stored_files SET expires_at = ? WHERE id = ?", System.currentTimeMillis() + days * DAY_MS, id);
            return file(id);
        }
    }

    public List<LinkItem> links() {
        return jdbc.query("SELECT " + LINK_COLUMNS + " FROM transfer_links ORDER BY created_at DESC", LINK_MAPPER);
    }

    public LinkItem link(String token) {
        return jdbc.query("SELECT " + LINK_COLUMNS + " FROM transfer_links WHERE token = ?", LINK_MAPPER, token)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "链接不存在"));
    }

    public LinkItem activeLink(String token, String kind) {
        LinkItem link = link(token);
        if (link.revoked() || link.expiresAt() <= System.currentTimeMillis()) {
            throw new ApiException(410, "链接已过期或已关闭");
        }
        if (kind != null && !kind.equals(link.kind())) {
            throw new ApiException(400, "链接类型不正确");
        }
        return link;
    }

    public LinkItem resolveCode(String code) {
        String normalized = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        LinkItem link = jdbc.query("SELECT " + LINK_COLUMNS + " FROM transfer_links WHERE pickup_code = ?", LINK_MAPPER, normalized)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "取件码不存在"));
        return activeLink(link.token(), null);
    }

    public boolean verifyLinkPassword(String token, String password) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM transfer_links WHERE token = ?", String.class, token);
        return hash == null || passwords.matches(password, hash);
    }

    public List<FileItem> shareFiles(String token) {
        return jdbc.query("SELECT f.id, f.original_name, f.size_bytes, f.inbox_token, f.expires_at, f.created_at "
                        + "FROM stored_files f JOIN share_files s ON f.id = s.file_id WHERE s.link_token = ? AND f.expires_at > ? ORDER BY f.created_at",
                FILE_MAPPER, token, System.currentTimeMillis());
    }

    public Map<String, Object> inboxUsage(String token) {
        synchronized (quotaLock) {
            return Map.of("usedBytes", usedBytes(token), "fileCount",
                    jdbc.queryForObject("SELECT COUNT(*) FROM stored_files WHERE inbox_token = ?", Long.class, token));
        }
    }

    public LinkItem createShare(ShareRequest request) {
        synchronized (quotaLock) {
            List<String> ids = request.fileIds().stream().distinct().toList();
            ids.forEach(this::availableFile);
            return transactions.execute(status -> {
                LinkItem link = insertLink("SHARE", request.title(), request.note(), request.password(), request.days(), 0, 0, 0);
                for (String id : ids) {
                    jdbc.update("INSERT INTO share_files (link_token, file_id) VALUES (?, ?)", link.token(), id);
                }
                return link;
            });
        }
    }

    public LinkItem createInbox(InboxRequest request) {
        synchronized (quotaLock) {
            Settings settings = settings();
            if (request.quotaBytes() > settings.quotaBytes() || request.maxFileBytes() > settings.maxFileBytes()
                    || request.maxFileBytes() > request.quotaBytes()) {
                throw new ApiException(400, "收件箱限制不能超过系统限制，单文件大小不能超过收件箱容量");
            }
            return insertLink("INBOX", request.title(), request.note(), request.password(), request.days(),
                    request.quotaBytes(), request.maxFiles(), request.maxFileBytes());
        }
    }

    public void revokeLink(String token) {
        synchronized (quotaLock) {
            link(token);
            jdbc.update("UPDATE transfer_links SET revoked = TRUE WHERE token = ?", token);
        }
    }

    private LinkItem insertLink(String kind, String title, String note, String password, int days,
                                long quota, int maxFiles, long maxFile) {
        String token = randomToken();
        String code;
        do {
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 10; index++) {
                builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (jdbc.queryForObject("SELECT COUNT(*) FROM transfer_links WHERE pickup_code = ?", Integer.class, code) > 0);
        String hash = null;
        if (password != null && !password.isBlank()) {
            validatePassword(password);
            hash = passwords.encode(password);
        }
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO transfer_links (token, pickup_code, kind, title, note, password_hash, expires_at, revoked, quota_bytes, max_files, max_file_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?, ?)",
                token, code, kind, title.trim(), note == null ? "" : note.trim(), hash,
                now + days * DAY_MS, quota, maxFiles, maxFile, now);
        return link(token);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void cleanup() throws IOException {
        synchronized (quotaLock) {
            long now = System.currentTimeMillis();
            int failures = 0;
            List<String> expiredUploads = jdbc.queryForList("SELECT id FROM upload_sessions WHERE expires_at <= ?", String.class, now);
            for (String id : expiredUploads) {
                try {
                    Files.deleteIfExists(root.resolve("uploads").resolve(id));
                    jdbc.update("DELETE FROM upload_sessions WHERE id = ?", id);
                    reservations.remove(id);
                } catch (IOException exception) { failures++; }
            }
            Set<String> uploads = new HashSet<>(jdbc.queryForList("SELECT id FROM upload_sessions", String.class));
            try (var staging = Files.list(root.resolve("uploads"))) {
                for (Path path : staging.toList()) {
                    if (!uploads.contains(path.getFileName().toString())) {
                        try { Files.deleteIfExists(path); }
                        catch (IOException exception) { failures++; }
                    }
                }
            }
            List<String> expired = jdbc.queryForList("SELECT id FROM stored_files WHERE expires_at <= ?", String.class, now);
            for (String id : expired) {
                try {
                    deleteFile(id);
                } catch (IOException exception) {
                    failures++;
                }
            }
            // Recover unreferenced blobs left by a crash between move and database commit.
            Set<String> known = new HashSet<>(jdbc.queryForList("SELECT id FROM stored_files", String.class));
            known.addAll(jdbc.queryForList("SELECT id FROM upload_sessions WHERE finished = FALSE", String.class));
            try (var files = Files.list(root.resolve("files"))) {
                for (Path path : files.toList()) {
                    if (!known.contains(path.getFileName().toString())) {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            failures++;
                        }
                    }
                }
            }
            if (failures > 0) {
                LOG.warn("本轮清理有 {} 个文件删除失败，将在下次重试", failures);
            }
        }
    }

    private long usedBytes(String inboxToken) {
        if (inboxToken == null) {
            return jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes), 0) FROM stored_files", Long.class);
        }
        return jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes), 0) FROM stored_files WHERE inbox_token = ?", Long.class, inboxToken);
    }

    private long reservedBytes(String inboxToken) {
        return reservations.values().stream()
                .filter(item -> inboxToken == null || inboxToken.equals(item.inboxToken()))
                .mapToLong(Reservation::bytes).sum();
    }

    static String cleanFilename(String name) {
        if (name == null) {
            throw new ApiException(400, "缺少文件名");
        }
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (clean.isBlank() || clean.equals(".") || clean.equals("..") || clean.length() > 240) {
            throw new ApiException(400, "文件名无效或过长");
        }
        return clean;
    }

    private static void validatePassword(String password) {
        if (password.length() < 4 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(400, "密码至少 4 个字符，UTF-8 编码后不超过 72 字节");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static LinkItem mapLink(ResultSet row) throws SQLException {
        return new LinkItem(row.getString("token"), row.getString("pickup_code"), row.getString("kind"),
                row.getString("title"), row.getString("note"), row.getString("password_hash") != null,
                row.getLong("expires_at"), row.getBoolean("revoked"), row.getLong("quota_bytes"),
                row.getInt("max_files"), row.getLong("max_file_bytes"), row.getLong("created_at"));
    }
}
