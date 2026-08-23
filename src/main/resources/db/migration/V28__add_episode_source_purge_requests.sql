CREATE TABLE episode_source_purge_requests (
    id UUID PRIMARY KEY,
    episode_id UUID NOT NULL,
    work_id UUID NOT NULL,
    previous_source_file_id UUID,
    previous_episode_no INTEGER NOT NULL,
    previous_content_key VARCHAR(512),
    previous_source_storage_url VARCHAR(512),
    retained_content_key VARCHAR(512) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    processing_started_at TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_episode_source_purge_episode UNIQUE (episode_id),
    CONSTRAINT fk_episode_source_purge_episode
        FOREIGN KEY (episode_id) REFERENCES episodes (id) ON DELETE CASCADE,
    CONSTRAINT ck_episode_source_purge_status
        CHECK (status IN ('REQUESTED', 'PROCESSING')),
    CONSTRAINT ck_episode_source_purge_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE INDEX idx_episode_source_purge_process
    ON episode_source_purge_requests (status, requested_at);
