ALTER TABLE world_setting_comparison_decisions
    ADD COLUMN root_property_moves_applied_world_setting_version BIGINT,
    ADD COLUMN root_property_moves_disabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_world_setting_comparison_decisions_root_move_applied_version
        CHECK (root_property_moves_applied_world_setting_version IS NULL
            OR root_property_moves_applied_world_setting_version >= 0),
    ADD CONSTRAINT ck_world_setting_comparison_decisions_root_moves_disabled
        CHECK (root_property_moves_disabled = FALSE
            OR jsonb_array_length(existing_root_property_move_snapshots) > 0);

ALTER TABLE world_setting_candidates
    DROP CONSTRAINT ck_world_setting_candidates_comparison_source_reason_code;

ALTER TABLE world_setting_candidates
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
                'SCOPE_REVIEW_MATCHED_PATH_INVALID',
                'BATCH_CONTEXT_NOT_INITIALIZED',
                'BATCH_DECISION_REF_DUPLICATED',
                'BATCH_SOURCE_REF_DUPLICATED',
                'BATCH_SOURCE_REF_UNKNOWN',
                'BATCH_SOURCE_COVERAGE_INVALID',
                'BATCH_RESOLVED_TARGET_COVERAGE_INVALID',
                'BATCH_CANONICAL_SUBJECT_INVALID',
                'BATCH_CONSOLIDATION_STATUS_INVALID',
                'BATCH_SOURCE_SCOPE_INVALID',
                'ROOT_PROPERTY_MOVE_NOT_ALLOWED',
                'ROOT_PROPERTY_MOVE_INVALID',
                'ROOT_PROPERTY_MOVE_DUPLICATED',
                'ROOT_PROPERTY_MOVE_CONFLICT',
                'SCOPE_SETTING_NAME_DUPLICATED',
                'SYNTHETIC_SCOPE_SINGLETON',
                'BATCH_PROPOSED_PATH_DUPLICATED'
            )
        );
