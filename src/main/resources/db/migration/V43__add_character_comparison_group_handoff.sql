ALTER TABLE analysis_jobs
    ADD COLUMN character_comparison_input_hash VARCHAR(64),
    ADD COLUMN character_comparisons_handed_off BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE setting_candidates
    ADD COLUMN confirmed_group_decision_hash VARCHAR(64);

ALTER TABLE character_fact_comparison_batches
    ALTER COLUMN matched_character_id DROP NOT NULL;

ALTER TABLE analysis_jobs
    ADD CONSTRAINT chk_analysis_jobs_character_comparison_input_hash
        CHECK (
            character_comparison_input_hash IS NULL
            OR character_comparison_input_hash ~ '^[0-9a-f]{64}$'
        );

ALTER TABLE setting_candidates
    ADD CONSTRAINT chk_setting_candidates_confirmed_group_decision_hash
        CHECK (
            confirmed_group_decision_hash IS NULL
            OR confirmed_group_decision_hash ~ '^[0-9a-f]{64}$'
        );

DROP INDEX uk_analysis_jobs_active_setting_candidate;

CREATE UNIQUE INDEX uk_analysis_jobs_active_setting_candidate
    ON analysis_jobs (setting_candidate_id)
    WHERE job_type = 'CHARACTER_FACT_COMPARISON'
      AND character_comparison_input_hash IS NULL
      AND status IN ('PENDING', 'RUNNING');

CREATE UNIQUE INDEX uk_analysis_jobs_active_character_comparison_input
    ON analysis_jobs (batch_id, character_comparison_input_hash)
    WHERE job_type = 'CHARACTER_FACT_COMPARISON'
      AND character_comparison_input_hash IS NOT NULL
      AND status IN ('PENDING', 'RUNNING');
