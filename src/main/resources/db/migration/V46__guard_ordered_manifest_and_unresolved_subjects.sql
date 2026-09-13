ALTER TABLE analysis_jobs ADD COLUMN source_episode_no INTEGER;
UPDATE analysis_jobs job SET source_episode_no = episode.episode_no
FROM episodes episode
WHERE job.episode_id = episode.id AND job.analysis_mode = 'ORDERED_PROVISIONAL';
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_source_episode_no
    CHECK ((analysis_mode = 'CONFIRMED_ONLY' AND source_episode_no IS NULL)
        OR (analysis_mode = 'ORDERED_PROVISIONAL' AND source_episode_no IS NOT NULL));
COMMENT ON COLUMN analysis_jobs.source_episode_no IS '누적 실행 생성 당시 회차 번호. 번호 변경으로 실행 순서가 조용히 바뀌는 것을 차단한다.';

ALTER TABLE analysis_jobs DROP CONSTRAINT ck_analysis_jobs_sealed_journal;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_sealed_journal
    CHECK (journal_status <> 'SEALED' OR (state_journal IS NOT NULL
        AND jsonb_typeof(state_journal) = 'object'
        AND input_state_hash IS NOT NULL AND input_state_hash ~ '^[0-9a-f]{64}$'
        AND state_journal ? 'changes' AND jsonb_typeof(state_journal -> 'changes') = 'array'
        AND state_journal ->> 'outputStateHash' IS NOT NULL
        AND state_journal ->> 'outputStateHash' ~ '^[0-9a-f]{64}$'));

-- NEW의 임시 식별자는 현재 Job에서 먼저 등록될 수 있다. 실제 FK는 생성하지 않는다.
-- AMBIGUOUS는 후보 대상을 특정하지 못한 경우에도 미해결 참고 기록으로 보존한다.
ALTER TABLE world_setting_comparison_batches DROP CONSTRAINT ck_world_setting_comparison_batches_resolved_target_ids;
ALTER TABLE world_setting_comparison_batches ADD CONSTRAINT ck_world_setting_comparison_batches_resolved_target_ids
    CHECK (jsonb_typeof(resolved_target_world_setting_ids) = 'array'
        AND jsonb_typeof(resolved_provisional_subject_keys) = 'array'
        AND ((subject_resolution_type = 'NEW'
                AND jsonb_array_length(resolved_target_world_setting_ids) = 0
                AND jsonb_array_length(resolved_provisional_subject_keys) <= 1)
            OR (subject_resolution_type = 'EXISTING'
                AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 1)
            OR (subject_resolution_type = 'AMBIGUOUS'
                AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) <> 1)));

ALTER TABLE world_setting_candidates DROP CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality
    CHECK (subject_resolution_type IS NULL
        OR (subject_resolution_type = 'NEW'
            AND jsonb_array_length(resolved_target_world_setting_ids) = 0
            AND jsonb_array_length(resolved_provisional_subject_keys) <= 1)
        OR (subject_resolution_type = 'EXISTING'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) = 1)
        OR (subject_resolution_type = 'AMBIGUOUS'
            AND jsonb_array_length(resolved_target_world_setting_ids) + jsonb_array_length(resolved_provisional_subject_keys) <> 1));

ALTER TABLE world_setting_comparison_decisions DROP CONSTRAINT ck_world_setting_comparison_decisions_review_reason;
ALTER TABLE world_setting_comparison_decisions ADD CONSTRAINT ck_world_setting_comparison_decisions_review_reason
    CHECK (comparison_review_reason IS NULL
        OR comparison_review_reason IN ('SCOPE_UNRESOLVED', 'BATCH_LIMIT_EXCEEDED', 'SUBJECT_UNRESOLVED'));
ALTER TABLE world_setting_candidates DROP CONSTRAINT ck_world_setting_candidates_comparison_review_reason;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_comparison_review_reason
    CHECK ((suggested_operation IS NOT NULL AND suggested_operation = 'REVIEW_REQUIRED'
            AND comparison_review_reason IS NOT NULL
            AND comparison_review_reason IN ('SCOPE_UNRESOLVED', 'BATCH_LIMIT_EXCEEDED', 'SUBJECT_UNRESOLVED'))
        OR ((suggested_operation IS NULL OR suggested_operation <> 'REVIEW_REQUIRED')
            AND comparison_review_reason IS NULL));
