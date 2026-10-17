ALTER TABLE works ADD COLUMN relationship_input_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE relationship_candidates
    ADD COLUMN origin VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN automatically_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN validated_input_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN automatic_review_reason VARCHAR(50);
CREATE INDEX idx_relationship_candidates_work_status ON relationship_candidates(work_id, status, created_at, id);

CREATE TABLE relationship_processing_states (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL CONSTRAINT fk_relationship_processing_work REFERENCES works(id),
    processed_version BIGINT NOT NULL DEFAULT 0,
    attempted_version BIGINT NOT NULL DEFAULT 0,
    last_processed_at TIMESTAMP,
    retry_at TIMESTAMP,
    failure_code VARCHAR(50),
    auto_confirmed_count INTEGER NOT NULL DEFAULT 0,
    review_required_count INTEGER NOT NULL DEFAULT 0,
    unsupported_source_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_relationship_processing_work UNIQUE(work_id)
);
