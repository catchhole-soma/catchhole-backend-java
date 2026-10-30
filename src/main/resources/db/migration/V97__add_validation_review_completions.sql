-- GH312 S2: review-completion receipt that releases exactly one SETTING_EXTRACTION job for the report.
-- The receipt and its job are written in one Work-locked transaction; report status values are unchanged (V91 CHECK).
CREATE TABLE validation_review_completions (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL UNIQUE REFERENCES validation_reports(id),
    work_id UUID NOT NULL REFERENCES works(id),
    episode_id UUID NOT NULL REFERENCES episodes(id),
    trigger VARCHAR(20) NOT NULL CHECK (trigger IN ('AUTOMATIC','EXPLICIT')),
    report_version BIGINT NOT NULL CHECK (report_version >= 0),
    idempotency_key VARCHAR(100),
    request_hash VARCHAR(64),
    extraction_job_id UUID NOT NULL REFERENCES analysis_jobs(id),
    extraction_job_generation BIGINT NOT NULL DEFAULT 1 CHECK (extraction_job_generation >= 1),
    completed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_review_completion_request CHECK (
        (trigger = 'AUTOMATIC' AND idempotency_key IS NULL AND request_hash IS NULL)
        OR (trigger = 'EXPLICIT' AND idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
);
CREATE UNIQUE INDEX uk_validation_review_completions_key ON validation_review_completions(work_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_validation_review_completions_work ON validation_review_completions(work_id);
CREATE INDEX idx_validation_review_completions_episode ON validation_review_completions(episode_id);
CREATE INDEX idx_validation_review_completions_job ON validation_review_completions(extraction_job_id);
COMMENT ON TABLE validation_review_completions IS 'One review-completion receipt per validation report. Created together with its SETTING_EXTRACTION job under the Work lock. Kept as history when the report is invalidated or retired; only the current report''s receipt releases extraction. Deleted before analysis_jobs and validation_reports on work purge.';
COMMENT ON COLUMN validation_review_completions.report_version IS 'Report version the server evaluated when completing review, before the completion advanced it.';
COMMENT ON COLUMN validation_review_completions.idempotency_key IS 'EXPLICIT only. Same key and request_hash replays the receipt; same key with another request is rejected.';
COMMENT ON COLUMN validation_review_completions.request_hash IS 'Hash of {reportId, expectedReportVersion} for the EXPLICIT request.';
COMMENT ON COLUMN validation_review_completions.extraction_job_generation IS 'Starts at 1 and increments when a public retry replaces the linked extraction job id.';
