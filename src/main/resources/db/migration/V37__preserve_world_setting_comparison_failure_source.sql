ALTER TABLE world_setting_candidates
    ADD COLUMN comparison_source_error_code VARCHAR(100),
    ADD COLUMN comparison_source_reason_code VARCHAR(100);

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_comparison_source_error_code
        CHECK (
            comparison_source_error_code IS NULL
            OR comparison_source_error_code ~ '^[A-Z][A-Z0-9_]*$'
        ),
    ADD CONSTRAINT ck_world_setting_candidates_comparison_source_reason_code
        CHECK (
            comparison_source_reason_code IS NULL
            OR comparison_source_reason_code IN (
                'CONTEXT_TARGET_DUPLICATED',
                'CONTEXT_TARGET_NOT_FOUND',
                'CONTEXT_VERSION_DUPLICATED',
                'EXACT_TARGET_NOT_IN_CONTEXT',
                'SELECTED_TARGET_NOT_IN_CONTEXT',
                'REVIEW_REASON_FORBIDDEN',
                'MATCHED_TARGET_REQUIRED',
                'PROPOSED_PATH_MISMATCH',
                'ADD_MATCHED_PATH_FORBIDDEN',
                'SCOPE_REVIEW_REQUIRED',
                'PROPOSED_PATH_CONFLICT',
                'EXCLUDE_MATCHED_PATH_INVALID',
                'EXCLUDE_MATCHED_SCOPE_WITHOUT_PROPERTY',
                'SCOPE_REVIEW_REASON_INVALID',
                'SCOPE_REVIEW_TARGET_REQUIRED',
                'SCOPE_REVIEW_CANDIDATE_ALREADY_SCOPED',
                'SCOPE_REVIEW_MATCHED_PATH_REQUIRED',
                'SCOPE_REVIEW_ROOT_PATH_EXISTS',
                'SCOPE_REVIEW_MATCHED_PATH_INVALID'
            )
        );
