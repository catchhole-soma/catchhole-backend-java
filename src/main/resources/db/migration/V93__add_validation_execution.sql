-- Dedicated validation execution never creates extraction jobs or candidates.
CREATE TABLE validation_jobs (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL UNIQUE REFERENCES validation_reports(id),
    input_digest VARCHAR(64) NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    configuration JSONB NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('QUEUED','RUNNING','PARTIAL','FAILED','COMPLETED','CANCELED')),
    generation BIGINT NOT NULL CHECK (generation > 0),
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    recoveries INTEGER NOT NULL CHECK (recoveries >= 0),
    lease_token UUID,
    worker_id VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    grid_ready BOOLEAN NOT NULL,
    required_grid JSONB,
    failure_code VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_running_lease CHECK (status <> 'RUNNING' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX idx_validation_jobs_claim ON validation_jobs(status,created_at,id);
CREATE TABLE validation_stages (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    branch VARCHAR(20) NOT NULL CHECK (branch IN ('SETTING','TYPO')),
    stage VARCHAR(30) NOT NULL,
    unit_ref VARCHAR(200) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    successful BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(report_id,branch,stage,unit_ref)
);
CREATE TABLE validation_checks (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    local_ref VARCHAR(100) NOT NULL,
    original_refs JSONB NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    input_digest VARCHAR(64) NOT NULL,
    change_reason VARCHAR(200),
    state JSONB NOT NULL,
    outcome VARCHAR(30),
    decision JSONB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(report_id,local_ref)
);
CREATE TABLE validation_check_history (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    check_id UUID NOT NULL REFERENCES validation_checks(id),
    revision BIGINT NOT NULL CHECK (revision > 0),
    input_digest VARCHAR(64) NOT NULL,
    change_reason VARCHAR(200) NOT NULL,
    outcome VARCHAR(30),
    state JSONB NOT NULL,
    decision JSONB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(check_id,revision)
);
CREATE TABLE validation_publications (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    request_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(report_id,request_key)
);
CREATE TABLE validation_checkpoints (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    branch VARCHAR(20) NOT NULL CHECK (branch IN ('SETTING','TYPO')),
    checkpoint_version BIGINT NOT NULL CHECK (checkpoint_version > 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(report_id,branch)
);
CREATE TABLE validation_findings (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    branch VARCHAR(20) NOT NULL CHECK (branch IN ('SETTING','TYPO')),
    unit_ref VARCHAR(200) NOT NULL,
    check_id UUID REFERENCES validation_checks(id),
    check_revision BIGINT,
    outcome VARCHAR(30) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_finding_owner CHECK ((branch='SETTING' AND check_id IS NOT NULL AND check_revision>0) OR (branch='TYPO' AND check_id IS NULL AND check_revision IS NULL))
);
CREATE TABLE validation_evidence (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    owner_ref VARCHAR(200) NOT NULL,
    source_ref VARCHAR(100) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    source_version VARCHAR(255),
    start_cp INTEGER NOT NULL CHECK (start_cp>=0),
    end_cp INTEGER NOT NULL CHECK (end_cp>start_cp),
    quote TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_validation_findings_report ON validation_findings(report_id);
CREATE INDEX idx_validation_evidence_report ON validation_evidence(report_id);
ALTER TABLE ai_token_usages
    ADD COLUMN validation_job_id UUID REFERENCES validation_jobs(id) ON DELETE CASCADE,
    ADD COLUMN validation_lease_token UUID,
    ADD COLUMN validation_generation BIGINT,
    DROP CONSTRAINT ck_ai_token_usage_owner,
    ADD CONSTRAINT ck_ai_token_usage_owner CHECK ((analysis_job_id IS NOT NULL)::integer + (chat_run_id IS NOT NULL)::integer + (validation_job_id IS NOT NULL)::integer = 1),
    ADD CONSTRAINT ck_ai_token_usage_validation_lease CHECK ((validation_job_id IS NULL AND validation_lease_token IS NULL AND validation_generation IS NULL) OR (validation_job_id IS NOT NULL AND validation_lease_token IS NOT NULL AND validation_generation>0)),
    ADD CONSTRAINT ck_ai_token_usage_validation_purpose CHECK ((validation_job_id IS NOT NULL) = (purpose LIKE 'VALIDATION_%'));
CREATE INDEX idx_ai_token_usages_validation ON ai_token_usages(validation_job_id,status);
ALTER TABLE validation_request_keys DROP CONSTRAINT ck_validation_request_kind;
ALTER TABLE validation_request_keys ADD CONSTRAINT ck_validation_request_kind CHECK (request_kind IN ('START','RECHECK','RETRY'));
COMMENT ON TABLE validation_jobs IS 'Real validation lease/input/limits owner. Frozen successful semantic stages survive same-input retries; source invalidation clears all copied result/checkpoint/evidence content.';
COMMENT ON COLUMN ai_token_usages.validation_lease_token IS 'Original genuine call lease, retained through UNKNOWN and report invalidation for exact late measured settlement; never grants a new reservation/publication.';
