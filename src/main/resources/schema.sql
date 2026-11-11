CREATE TABLE IF NOT EXISTS app_settings (
  id INT PRIMARY KEY CHECK (id = 1),
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  quota_bytes BIGINT NOT NULL,
  max_file_bytes BIGINT NOT NULL,
  retention_days INT NOT NULL
);
CREATE TABLE IF NOT EXISTS transfer_links (
  token VARCHAR(64) PRIMARY KEY,
  pickup_code VARCHAR(16) NOT NULL UNIQUE,
  kind VARCHAR(8) NOT NULL,
  title VARCHAR(120) NOT NULL,
  note VARCHAR(500) NOT NULL,
  password_hash VARCHAR(100),
  expires_at BIGINT NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  quota_bytes BIGINT NOT NULL,
  max_files INT NOT NULL,
  max_file_bytes BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS stored_files (
  id VARCHAR(36) PRIMARY KEY,
  original_name VARCHAR(240) NOT NULL,
  size_bytes BIGINT NOT NULL,
  inbox_token VARCHAR(64) REFERENCES transfer_links(token),
  expires_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS share_files (
  link_token VARCHAR(64) NOT NULL REFERENCES transfer_links(token),
  file_id VARCHAR(36) NOT NULL REFERENCES stored_files(id) ON DELETE CASCADE,
  PRIMARY KEY(link_token, file_id)
);
CREATE INDEX IF NOT EXISTS idx_files_expiry ON stored_files(expires_at);
CREATE INDEX IF NOT EXISTS idx_files_inbox ON stored_files(inbox_token);
CREATE TABLE IF NOT EXISTS upload_sessions (
  id VARCHAR(36) PRIMARY KEY,
  original_name VARCHAR(240) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  inbox_token VARCHAR(64),
  retention_days INT NOT NULL,
  expires_at BIGINT NOT NULL,
  finished BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS upload_parts (
  upload_id VARCHAR(36) REFERENCES upload_sessions(id) ON DELETE CASCADE,
  part_index INT NOT NULL,
  PRIMARY KEY(upload_id, part_index)
);
