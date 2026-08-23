ALTER TABLE works
    ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE works
    ADD CONSTRAINT ck_works_lifecycle_status
        CHECK (lifecycle_status IN ('ACTIVE', 'PURGING'));

CREATE TABLE work_purge_requests (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    member_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    processing_started_at TIMESTAMP,
    worker_drain_until TIMESTAMP,
    completed_at TIMESTAMP,
    retention_expires_at TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_code VARCHAR(80),
    s3_target_count INTEGER NOT NULL DEFAULT 0,
    s3_deleted_count INTEGER NOT NULL DEFAULT 0,
    s3_failed_count INTEGER NOT NULL DEFAULT 0,
    db_target_count INTEGER NOT NULL DEFAULT 0,
    db_deleted_count INTEGER NOT NULL DEFAULT 0,
    db_failed_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_work_purge_requests_work UNIQUE (work_id),
    CONSTRAINT fk_work_purge_requests_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT ck_work_purge_requests_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT ck_work_purge_requests_counts_non_negative
        CHECK (
            attempt_count >= 0
            AND s3_target_count >= 0
            AND s3_deleted_count >= 0
            AND s3_failed_count >= 0
            AND db_target_count >= 0
            AND db_deleted_count >= 0
            AND db_failed_count >= 0
        )
);

CREATE INDEX idx_work_purge_requests_member_requested
    ON work_purge_requests (member_id, requested_at DESC);

CREATE INDEX idx_work_purge_requests_process
    ON work_purge_requests (status, worker_drain_until, requested_at);
