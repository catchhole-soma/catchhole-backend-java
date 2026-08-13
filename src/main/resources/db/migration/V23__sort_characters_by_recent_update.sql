DROP INDEX IF EXISTS idx_characters_work_status_created_id;

CREATE INDEX idx_characters_work_status_updated_id
    ON characters (work_id, status, updated_at DESC, id DESC);
