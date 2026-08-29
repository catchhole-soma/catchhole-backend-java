ALTER TABLE world_setting_candidates
    ADD COLUMN matched_scope_name VARCHAR(100),
    ADD COLUMN matched_property_name VARCHAR(100),
    ADD COLUMN comparison_review_reason VARCHAR(40);

ALTER TABLE world_setting_candidates
    DROP CONSTRAINT ck_world_setting_candidates_suggested_operation;

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_suggested_operation
        CHECK (
            suggested_operation IS NULL
            OR suggested_operation IN ('ADD', 'UPDATE', 'MERGE', 'EXCLUDE', 'REVIEW_REQUIRED')
        ),
    ADD CONSTRAINT ck_world_setting_candidates_comparison_review_reason
        CHECK (
            (suggested_operation = 'REVIEW_REQUIRED'
                AND comparison_review_reason = 'SCOPE_UNRESOLVED')
            OR ((suggested_operation IS NULL OR suggested_operation <> 'REVIEW_REQUIRED')
                AND comparison_review_reason IS NULL)
        );
