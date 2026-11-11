package cn.gugupan;

import static cn.gugupan.Models.*;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChunkService {
    public static final int CHUNK_SIZE = 4 * 1024 * 1024;
    private record Upload(String id, String name, long size, String hash, String inbox, int days,
                          long expiresAt, boolean finished) {}
    private final PanService files;
    private final JdbcTemplate jdbc;
    private final Path directory;

    public ChunkService(PanService files, JdbcTemplate jdbc, PanProperties properties) {
        this.files = files;
        this.jdbc = jdbc;
        this.directory = properties.dataDir().toAbsolutePath().normalize().resolve("uploads");
    }

    public Map<String, Object> create(UploadRequest request, String inbox) throws IOException {
        synchronized (files.quotaLock()) {
            String name = PanService.cleanFilename(request.name());
            String id;
            try { id = UUID.fromString(request.id()).toString(); }
            catch (IllegalArgumentException exception) { throw new ApiException(400, "上传标识无效"); }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM upload_sessions WHERE id = ?", Integer.class, id) > 0) {
                Upload existing = owned(id, inbox);
                if (!existing.hash().equals(request.sha256()) || existing.size() != request.size() || !existing.name().equals(name))
                    throw new ApiException(409, "上传任务与文件不匹配");
                return status(id, inbox);
            }
            if (Files.exists(files.filePath(id)) || jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stored_files WHERE id = ?", Integer.class, id) > 0)
                throw new ApiException(409, "上传标识已被使用，请重新选择文件");
            int days = files.reserveUpload(id, request.size(), request.days(), inbox);
            try {
                Files.createFile(directory.resolve(id));
                jdbc.update("INSERT INTO upload_sessions (id, original_name, size_bytes, sha256, inbox_token, retention_days, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        id, name, request.size(), request.sha256(), inbox, days, System.currentTimeMillis() + 86_400_000);
            } catch (IOException | RuntimeException exception) {
                files.releaseUpload(id);
                Files.deleteIfExists(directory.resolve(id));
                throw exception;
            }
            return status(id, inbox);
        }
    }

    public Map<String, Object> status(String id, String inbox) {
        synchronized (files.quotaLock()) {
            Upload upload = owned(id, inbox);
            return Map.of("id", id, "chunkSize", CHUNK_SIZE, "size", upload.size(), "sha256", upload.hash(),
                    "finished", upload.finished(), "expiresAt", upload.expiresAt(),
                    "uploaded", jdbc.queryForList("SELECT part_index FROM upload_parts WHERE upload_id = ? ORDER BY part_index", Integer.class, id));
        }
    }

    public void part(String id, String inbox, int index, String hash, InputStream input) throws IOException {
        Upload upload;
        synchronized (files.quotaLock()) {
            upload = owned(id, inbox);
            if (upload.finished()) throw new ApiException(409, "上传已经完成");
        }
        long offset = (long) index * CHUNK_SIZE;
        if (index < 0 || offset >= upload.size()) throw new ApiException(400, "分片编号无效");
        int expected = (int) Math.min(CHUNK_SIZE, upload.size() - offset);
        // Slow request bodies must not hold the lock needed by quota checks and cancellation.
        byte[] bytes = input.readNBytes(expected + 1);
        if (bytes.length != expected) throw new ApiException(400, "分片大小不正确，请重试");
        if (!hex(digest().digest(bytes)).equals(hash)) throw new ApiException(400, "分片校验失败，请重试");
        synchronized (files.quotaLock()) {
            if (owned(id, inbox).finished()) throw new ApiException(409, "上传已经完成");
            try (RandomAccessFile file = new RandomAccessFile(directory.resolve(id).toFile(), "rw")) {
                file.seek(offset);
                file.write(bytes);
                file.getChannel().force(true);
            }
            jdbc.update("MERGE INTO upload_parts (upload_id, part_index) KEY(upload_id, part_index) VALUES (?, ?)", id, index);
        }
    }

    public FileItem finish(String id, String inbox) throws IOException {
        synchronized (files.quotaLock()) {
            Upload upload = owned(id, inbox);
            if (upload.finished()) return files.availableFile(id);
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM upload_parts WHERE upload_id = ?", Long.class, id);
            if (count != (upload.size() + CHUNK_SIZE - 1) / CHUNK_SIZE) throw new ApiException(409, "还有分片未上传完成");
            Path staging = directory.resolve(id);
            Path source = Files.exists(staging) ? staging : files.filePath(id);
            if (!Files.exists(source) || Files.size(source) != upload.size()) throw new ApiException(409, "上传数据不完整，请重新上传");
            MessageDigest digest = digest();
            try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            if (!hex(digest.digest()).equals(upload.hash())) throw new ApiException(409, "文件校验失败，请取消后重新上传");
            return files.finishUpload(id, upload.name(), upload.size(), upload.days(), inbox, staging);
        }
    }

    public void cancel(String id, String inbox) throws IOException {
        synchronized (files.quotaLock()) {
            Upload upload = owned(id, inbox);
            Files.deleteIfExists(directory.resolve(id));
            if (!upload.finished()) Files.deleteIfExists(files.filePath(id));
            jdbc.update("DELETE FROM upload_sessions WHERE id = ?", id);
            files.releaseUpload(id);
        }
    }

    private Upload owned(String id, String inbox) {
        Upload upload = jdbc.query("SELECT id, original_name, size_bytes, sha256, inbox_token, retention_days, expires_at, finished FROM upload_sessions WHERE id = ?",
                (row, index) -> new Upload(row.getString(1), row.getString(2), row.getLong(3), row.getString(4),
                        row.getString(5), row.getInt(6), row.getLong(7), row.getBoolean(8)), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "上传任务不存在，请重新选择文件"));
        if (!Objects.equals(upload.inbox(), inbox)) throw new ApiException(404, "上传任务不存在");
        if (upload.expiresAt() <= System.currentTimeMillis()) throw new ApiException(410, "上传任务已过期，请重新上传");
        if (inbox != null) files.activeLink(inbox, "INBOX");
        return upload;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
