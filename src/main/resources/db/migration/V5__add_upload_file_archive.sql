ALTER TABLE upload_files
    ADD COLUMN archived_at TIMESTAMP;

CREATE INDEX idx_upload_files_active_setting_books
    ON upload_files (batch_id, file_role, archived_at);
