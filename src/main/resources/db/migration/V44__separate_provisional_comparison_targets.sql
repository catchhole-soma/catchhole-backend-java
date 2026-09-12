ALTER TABLE setting_candidates ADD COLUMN provisional_subject_key VARCHAR(160);
ALTER TABLE setting_candidates ADD CONSTRAINT ck_setting_candidates_provisional_identity
    CHECK (matched_character_id IS NULL OR provisional_subject_key IS NULL);

ALTER TABLE character_fact_comparison_batches
    ALTER COLUMN matched_character_id DROP NOT NULL,
    ADD COLUMN provisional_subject_key VARCHAR(160),
    ADD COLUMN analysis_context_snapshot_json JSONB;
ALTER TABLE character_fact_comparison_batches ADD CONSTRAINT ck_character_comparison_target_identity
    CHECK ((matched_character_id IS NOT NULL) <> (provisional_subject_key IS NOT NULL));

ALTER TABLE world_setting_candidates
    ADD COLUMN provisional_subject_key VARCHAR(160),
    ADD COLUMN resolved_provisional_subject_keys JSONB NOT NULL DEFAULT '[]';
ALTER TABLE world_setting_comparison_batches
    ADD COLUMN resolved_provisional_subject_keys JSONB NOT NULL DEFAULT '[]';
ALTER TABLE world_setting_comparison_decisions ADD COLUMN provisional_subject_key VARCHAR(160);

ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_candidates_provisional_identity
    CHECK (target_world_setting_id IS NULL OR provisional_subject_key IS NULL);
ALTER TABLE world_setting_comparison_decisions ADD CONSTRAINT ck_world_decisions_provisional_identity
    CHECK (target_world_setting_id IS NULL OR provisional_subject_key IS NULL);
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_candidates_provisional_keys
    CHECK (jsonb_typeof(resolved_provisional_subject_keys) = 'array');

ALTER TABLE world_setting_comparison_batches DROP CONSTRAINT ck_world_setting_comparison_batches_resolved_target_ids;
ALTER TABLE world_setting_comparison_batches ADD CONSTRAINT ck_world_setting_comparison_batches_resolved_target_ids
    CHECK (jsonb_typeof(resolved_target_world_setting_ids) = 'array'
        AND jsonb_typeof(resolved_provisional_subject_keys) = 'array'
        AND ((subject_resolution_type = 'NEW'
                AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 0)
            OR (subject_resolution_type = 'EXISTING'
                AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 1)
            OR (subject_resolution_type = 'AMBIGUOUS'
                AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) > 1)));

ALTER TABLE world_setting_candidates DROP CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality
    CHECK (subject_resolution_type IS NULL
        OR (subject_resolution_type = 'NEW'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 0)
        OR (subject_resolution_type = 'EXISTING'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 1)
        OR (subject_resolution_type = 'AMBIGUOUS'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) > 1));

CREATE INDEX idx_setting_candidates_provisional_subject ON setting_candidates (analysis_job_id, provisional_subject_key)
    WHERE provisional_subject_key IS NOT NULL;
COMMENT ON COLUMN setting_candidates.provisional_subject_key IS '실제 WorkCharacter FK와 구분한 실행 내부 임시 인물 참조';
COMMENT ON COLUMN character_fact_comparison_batches.analysis_context_snapshot_json IS '검증 당시 누적 대상 문맥. 사용자 확정 시 현재 정식 설정과 의존성을 대조한다.';
