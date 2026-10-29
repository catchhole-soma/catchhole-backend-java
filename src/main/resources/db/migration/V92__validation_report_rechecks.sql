-- Reports are immutable-input revisions; is_current selects a single pointer under the Work lock.
ALTER TABLE episodes ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 0 CHECK (content_revision >= 0);
ALTER TABLE validation_reports DROP CONSTRAINT validation_reports_episode_id_key;
ALTER TABLE validation_reports
    ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    ADD COLUMN baseline_revision BIGINT NOT NULL DEFAULT 1 CHECK (baseline_revision > 0),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN previous_report_id UUID,
    ADD COLUMN mode VARCHAR(30) NOT NULL DEFAULT 'BEFORE_CONFIRMATION' CHECK (mode IN ('BEFORE_CONFIRMATION','DIAGNOSTIC_ONLY')),
    ADD COLUMN previous_application_kind VARCHAR(40),
    ADD COLUMN previous_application_id UUID,
    ADD COLUMN previous_application_job_id UUID,
    ADD COLUMN previous_application_at TIMESTAMP,
    ADD COLUMN source_content_revision BIGINT NOT NULL DEFAULT 0 CHECK (source_content_revision >= 0),
    ADD COLUMN baseline_fingerprint VARCHAR(64),
    ADD CONSTRAINT ck_validation_diagnostic_receipt CHECK ((mode = 'DIAGNOSTIC_ONLY') = (previous_application_id IS NOT NULL AND previous_application_kind IS NOT NULL));
UPDATE validation_reports SET baseline_fingerprint = baseline_hash;
CREATE UNIQUE INDEX uk_validation_report_current ON validation_reports(episode_id) WHERE is_current;
CREATE UNIQUE INDEX uk_validation_report_revision ON validation_reports(episode_id,revision);
CREATE INDEX idx_validation_report_previous ON validation_reports(previous_report_id);
ALTER TABLE validation_request_keys ADD COLUMN request_hash VARCHAR(64), ADD COLUMN request_kind VARCHAR(20) NOT NULL DEFAULT 'START';
UPDATE validation_request_keys k SET request_hash = r.request_hash FROM validation_reports r WHERE r.id = k.report_id;
ALTER TABLE validation_request_keys ALTER COLUMN request_hash SET NOT NULL;
ALTER TABLE validation_request_keys ADD CONSTRAINT ck_validation_request_kind CHECK (request_kind IN ('START','RECHECK'));
CREATE TABLE validation_corrections (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id),
    report_id UUID NOT NULL REFERENCES validation_reports(id) ON DELETE CASCADE,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    report_version BIGINT NOT NULL CHECK (report_version >= 0),
    baseline_revision BIGINT NOT NULL CHECK (baseline_revision > 0),
    baseline_hash VARCHAR(64) NOT NULL,
    entry_ref VARCHAR(30) NOT NULL,
    entry_hash VARCHAR(64) NOT NULL,
    entity_version BIGINT NOT NULL CHECK (entity_version >= 0),
    correction_scope JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_validation_correction_request UNIQUE(work_id,request_key)
);
CREATE INDEX idx_validation_correction_report ON validation_corrections(report_id);
COMMENT ON COLUMN validation_reports.baseline_fingerprint IS 'Hash-only identity retained after copied baseline content is purged; never authorizes a read or extraction.';
COMMENT ON TABLE validation_corrections IS 'Actual current-setting edit receipt and exact correction scope; copied content follows validation source/settings/work purge.';
