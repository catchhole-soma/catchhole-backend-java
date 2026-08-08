ALTER TABLE analysis_jobs
    ADD COLUMN world_setting_candidate_id UUID,
    ADD COLUMN checkpoint_stage VARCHAR(50),
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMP,
    ADD COLUMN claim_attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE analysis_jobs
    ADD CONSTRAINT fk_analysis_jobs_world_setting_candidate
        FOREIGN KEY (world_setting_candidate_id)
        REFERENCES world_setting_candidates (id)
        ON DELETE SET NULL,
    ADD CONSTRAINT chk_analysis_jobs_claim_attempt_non_negative
        CHECK (claim_attempt_count >= 0);

CREATE UNIQUE INDEX uk_analysis_jobs_active_world_setting_candidate
    ON analysis_jobs (world_setting_candidate_id)
    WHERE world_setting_candidate_id IS NOT NULL
      AND status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_analysis_jobs_worker_claim
    ON analysis_jobs (job_type, status, created_at);

CREATE INDEX idx_analysis_jobs_lease_expiry
    ON analysis_jobs (lease_expires_at)
    WHERE status = 'RUNNING';
