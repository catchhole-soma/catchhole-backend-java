-- Support stable paging for active characters within a work.
CREATE INDEX idx_characters_work_status_created_id
    ON characters (work_id, status, created_at DESC, id DESC);
