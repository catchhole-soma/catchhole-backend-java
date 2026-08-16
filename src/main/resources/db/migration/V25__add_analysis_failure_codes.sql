ALTER TABLE analysis_jobs
    ADD COLUMN failure_code VARCHAR(60);

ALTER TABLE world_setting_candidates
    ADD COLUMN comparison_failure_code VARCHAR(60);

ALTER TABLE setting_candidates
    ADD COLUMN comparison_failure_code VARCHAR(60);

CREATE INDEX idx_world_setting_candidates_token_interrupted
    ON world_setting_candidates (work_id, analysis_job_id, created_at, id)
    WHERE review_status = 'PENDING_REVIEW'
      AND comparison_status = 'FAILED'
      AND comparison_failure_code = 'AI_TOKEN_QUOTA_EXHAUSTED';
