package cn.gugupan;

import jakarta.validation.constraints.*;
import java.util.List;

public final class Models {
    private Models() {}

    public record FileItem(String id, String name, long size, String inboxToken,
                           long expiresAt, long createdAt) {}

    public record LinkItem(String token, String code, String kind, String title, String note,
                           boolean passwordProtected, long expiresAt, boolean revoked,
                           long quotaBytes, int maxFiles, long maxFileBytes, long createdAt) {}

    public record Settings(String username, long quotaBytes, long maxFileBytes, int retentionDays) {}

    public record ShareRequest(@NotEmpty @Size(max = 100) List<@NotBlank String> fileIds,
                               @NotBlank @Size(max = 120) String title,
                               @Size(max = 500) String note, @Size(max = 64) String password,
                               @Min(1) @Max(30) int days) {}

    public record InboxRequest(@NotBlank @Size(max = 120) String title,
                               @Size(max = 500) String note, @Size(max = 64) String password,
                               @Min(1) @Max(30) int days, @Min(1) @Max(1125899906842624L) long quotaBytes,
                               @Min(1) @Max(1000) int maxFiles, @Min(1) @Max(1125899906842624L) long maxFileBytes) {}

    public record PasswordRequest(@NotBlank @Size(max = 128) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                         @NotBlank @Size(min = 10, max = 64) String newPassword) {}
    public record RetentionRequest(@Min(1) @Max(365) int days) {}
    public record SettingsRequest(@Min(1) @Max(1125899906842624L) long quotaBytes, @Min(1) @Max(1125899906842624L) long maxFileBytes,
                                   @Min(1) @Max(365) int retentionDays) {}
    public record CodeRequest(@NotBlank @Size(max = 16) String code) {}
    public record TextRequest(@NotBlank @Size(max = 120) String title,
                              @NotBlank @Size(max = 100000) String content,
                              @Size(max = 64) String password, @Min(1) @Max(30) int days) {}
    public record UploadRequest(@NotNull @Pattern(regexp = "[a-f0-9-]{36}") String id, @NotBlank @Size(max = 240) String name,
                                @Min(0) @Max(1125899906842624L) long size,
                                @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256,
                                @Min(1) @Max(365) Integer days) {}
}
