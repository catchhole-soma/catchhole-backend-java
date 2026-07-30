CREATE INDEX idx_analysis_jobs_work_batch_created
    ON analysis_jobs (work_id, batch_id, created_at DESC, id DESC);

CREATE INDEX idx_setting_candidates_job_review
    ON setting_candidates (analysis_job_id, review_status);
