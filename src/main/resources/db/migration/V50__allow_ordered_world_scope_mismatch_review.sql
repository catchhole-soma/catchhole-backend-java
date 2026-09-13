ALTER TABLE world_setting_comparison_decisions
    DROP CONSTRAINT ck_world_setting_comparison_decisions_review_reason;
ALTER TABLE world_setting_comparison_decisions
    ADD CONSTRAINT ck_world_setting_comparison_decisions_review_reason
    CHECK (comparison_review_reason IS NULL
        OR comparison_review_reason IN ('SCOPE_UNRESOLVED', 'SCOPE_MISMATCH', 'BATCH_LIMIT_EXCEEDED', 'SUBJECT_UNRESOLVED'));

ALTER TABLE world_setting_candidates
    DROP CONSTRAINT ck_world_setting_candidates_comparison_review_reason;
ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_comparison_review_reason
    CHECK ((suggested_operation IS NOT NULL AND suggested_operation = 'REVIEW_REQUIRED'
            AND comparison_review_reason IS NOT NULL
            AND comparison_review_reason IN ('SCOPE_UNRESOLVED', 'SCOPE_MISMATCH', 'BATCH_LIMIT_EXCEEDED', 'SUBJECT_UNRESOLVED'))
        OR ((suggested_operation IS NULL OR suggested_operation <> 'REVIEW_REQUIRED')
            AND comparison_review_reason IS NULL));
