ALTER TABLE analysis_jobs ADD COLUMN failure_diagnostics jsonb;
ALTER TABLE character_fact_comparison_batches ADD COLUMN failure_diagnostics jsonb;
ALTER TABLE world_setting_comparison_batches ADD COLUMN failure_diagnostics jsonb;
ALTER TABLE setting_candidates
    ADD COLUMN comparison_failure_diagnostics jsonb,
    ADD COLUMN comparison_failure_analysis_job_id uuid,
    ADD COLUMN comparison_failure_batch_id uuid;
ALTER TABLE world_setting_candidates
    ADD COLUMN comparison_failure_diagnostics jsonb,
    ADD COLUMN comparison_failure_analysis_job_id uuid,
    ADD COLUMN comparison_failure_batch_id uuid;
