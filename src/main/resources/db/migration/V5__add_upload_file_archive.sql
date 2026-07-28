ALTER TABLE upload_files
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_upload_files_active_setting_books
    ON upload_files (batch_id, file_role, archived_at);
