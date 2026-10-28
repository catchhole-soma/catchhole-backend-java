-- Undeployed GH303 foundation: validation precedes extraction. No extraction/candidate fields or release states.
ALTER TABLE episodes ADD COLUMN validation_upload_eligible BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE validation_reports (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id),
    member_id BIGINT NOT NULL REFERENCES members(id),
    batch_id UUID NOT NULL REFERENCES upload_batches(id),
    episode_id UUID NOT NULL UNIQUE REFERENCES episodes(id),
    source_episode_no INTEGER NOT NULL CHECK (source_episode_no > 0),
    source_content_hash VARCHAR(64) NOT NULL,
    source_content_s3_key VARCHAR(512) NOT NULL,
    source_content_s3_version VARCHAR(255),
    request_hash VARCHAR(64) NOT NULL,
    baseline_seed JSONB,
    baseline_hash VARCHAR(64),
    baseline_entries JSONB,
    confirmed_state_hash VARCHAR(64),
    baseline_status VARCHAR(20) NOT NULL CHECK (baseline_status IN ('PENDING','READY','FAILED','INVALIDATED')),
    status VARCHAR(40) NOT NULL CHECK (status IN ('PREPARING_BASELINE','WAITING_FOR_ERROR_REVIEW','HELD_INCOMPLETE','INVALIDATED')),
    hold_reason VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_reports_ready_baseline CHECK (baseline_status <> 'READY' OR (baseline_seed IS NOT NULL AND baseline_entries IS NOT NULL AND baseline_hash IS NOT NULL))
);
CREATE INDEX idx_validation_reports_work ON validation_reports(work_id);
CREATE INDEX idx_validation_reports_batch ON validation_reports(batch_id);
CREATE INDEX idx_validation_reports_member ON validation_reports(member_id);
CREATE TABLE validation_request_keys (
    id UUID PRIMARY KEY, work_id UUID NOT NULL REFERENCES works(id), request_key VARCHAR(100) NOT NULL,
    report_id UUID NOT NULL REFERENCES validation_reports(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_validation_request_keys UNIQUE (work_id, request_key)
);
CREATE TABLE validation_baseline_sources (
    id UUID PRIMARY KEY, report_id UUID NOT NULL REFERENCES validation_reports(id) ON DELETE CASCADE,
    episode_id UUID REFERENCES episodes(id), setting_book_revision_id UUID REFERENCES setting_book_revisions(id),
    source_manifest JSONB NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_baseline_sources_origin CHECK ((episode_id IS NULL) <> (setting_book_revision_id IS NULL))
);
CREATE INDEX idx_validation_baseline_sources_report ON validation_baseline_sources(report_id);
CREATE INDEX idx_validation_baseline_sources_episode ON validation_baseline_sources(episode_id);
CREATE INDEX idx_validation_baseline_sources_revision ON validation_baseline_sources(setting_book_revision_id);
COMMENT ON TABLE validation_reports IS 'Error-first foundation only. Baseline READY is neither completed error review nor permission to start extraction.';
