-- GH312 S3: whole-episode settings application receipt for a review-released extraction job.
-- One row per report. Character and world candidates are applied together in one Work-locked transaction; the row is APPLIED
-- only after both domains succeeded, otherwise HELD_REVIEW with structured reasons and no confirmed-setting writes.
CREATE TABLE validation_settings_applications (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL UNIQUE REFERENCES validation_reports(id),
    work_id UUID NOT NULL REFERENCES works(id),
    completion_id UUID NOT NULL REFERENCES validation_review_completions(id),
    extraction_job_id UUID NOT NULL REFERENCES analysis_jobs(id),
    extraction_job_generation BIGINT NOT NULL CHECK (extraction_job_generation >= 1),
    status VARCHAR(20) NOT NULL CHECK (status IN ('HELD_REVIEW','APPLIED','FAILED')),
    hold_reasons JSONB,
    attempt BIGINT NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    trigger VARCHAR(20) NOT NULL CHECK (trigger IN ('AUTOMATIC','EXPLICIT')),
    idempotency_key VARCHAR(100),
    request_hash VARCHAR(64),
    last_error_code VARCHAR(60),
    report_version BIGINT NOT NULL CHECK (report_version >= 0),
    applied_at TIMESTAMP,
    version BIGINT NOT NULL CHECK (version >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_settings_application_applied CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL)),
    CONSTRAINT ck_validation_settings_application_holds CHECK ((status = 'HELD_REVIEW') = (hold_reasons IS NOT NULL)),
    CONSTRAINT ck_validation_settings_application_request CHECK (
        (trigger = 'AUTOMATIC' AND idempotency_key IS NULL AND request_hash IS NULL)
        OR (trigger = 'EXPLICIT' AND idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
);
CREATE UNIQUE INDEX uk_validation_settings_applications_key ON validation_settings_applications(work_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_validation_settings_applications_work ON validation_settings_applications(work_id);
CREATE INDEX idx_validation_settings_applications_completion ON validation_settings_applications(completion_id);
CREATE INDEX idx_validation_settings_applications_job ON validation_settings_applications(extraction_job_id);
COMMENT ON TABLE validation_settings_applications IS 'One whole-episode settings application receipt per validation report (GH312 S3). APPLIED only after character and world candidates were written in one transaction. Kept as history when the report is invalidated or retired. Deleted before validation_review_completions, analysis_jobs and validation_reports on work purge.';
COMMENT ON COLUMN validation_settings_applications.hold_reasons IS 'HELD_REVIEW only. Array of {code, candidateType, candidateId?, detail?}. No setting was written for this attempt.';
COMMENT ON COLUMN validation_settings_applications.attempt IS 'Number of evaluations that recorded this receipt. Replays of the same key and requests after APPLIED do not add an attempt.';
COMMENT ON COLUMN validation_settings_applications.idempotency_key IS 'Latest EXPLICIT request key. Same key and request_hash replays the stored receipt; an AUTOMATIC attempt clears it.';
COMMENT ON COLUMN validation_settings_applications.request_hash IS 'Hash of {reportId, expectedReportVersion} for the EXPLICIT request.';
COMMENT ON COLUMN validation_settings_applications.last_error_code IS 'AppException code (or UNEXPECTED_ERROR) of the attempt whose application transaction was rolled back.';
COMMENT ON COLUMN validation_settings_applications.report_version IS 'Report version after this attempt advanced it, so replays return the same response.';
COMMENT ON COLUMN validation_settings_applications.status IS 'HELD_REVIEW or APPLIED. FAILED is reserved and not written in S3; rolled-back attempts stay HELD_REVIEW with UNKNOWN_ERROR.';
-- The automatic stage of a held release job is finished without writing settings. automatic_applied_at stays NULL
-- (it is the actual-application evidence used by diagnostic rechecks); this marker opens direct candidate review and saved decisions.
ALTER TABLE analysis_jobs ADD COLUMN review_application_held_at TIMESTAMP;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_job_review_application_held CHECK (review_application_held_at IS NULL OR automatic_applied_at IS NULL);
COMMENT ON COLUMN analysis_jobs.review_application_held_at IS 'GH312 S3: release job whose whole-episode settings application is HELD_REVIEW. Cleared when APPLIED sets automatic_applied_at.';
