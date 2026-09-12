ALTER TABLE setting_candidates ADD COLUMN preparation_failure_stage VARCHAR(40);
ALTER TABLE world_setting_candidates ADD COLUMN preparation_failure_stage VARCHAR(40);

ALTER TABLE world_setting_candidates DROP CONSTRAINT ck_world_setting_candidates_subject_resolution_type;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_type
    CHECK (subject_resolution_type IS NULL OR subject_resolution_type IN ('NEW', 'EXISTING', 'AMBIGUOUS', 'FAILED'));
ALTER TABLE world_setting_candidates DROP CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality
    CHECK (subject_resolution_type IS NULL
        OR (subject_resolution_type = 'NEW'
            AND jsonb_array_length(resolved_target_world_setting_ids) = 0
            AND jsonb_array_length(resolved_provisional_subject_keys) <= 1)
        OR (subject_resolution_type = 'EXISTING'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 1)
        OR (subject_resolution_type = 'AMBIGUOUS'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) <> 1)
        OR (subject_resolution_type = 'FAILED'
            AND jsonb_array_length(resolved_target_world_setting_ids) = 0
            AND jsonb_array_length(resolved_provisional_subject_keys) = 0
            AND (review_status <> 'PENDING_REVIEW' OR target_world_setting_id IS NULL)
            AND provisional_subject_key IS NULL
            AND preparation_failure_stage IS NOT NULL AND preparation_failure_stage = 'SUBJECT_RESOLUTION'));
