-- 기존 row를 backfill하지 않는다. FK 없는 값은 실패 당시 연결을 보존하는 snapshot이다.
ALTER TABLE analysis_failure_notifications
    ADD COLUMN failure_diagnostics jsonb,
    ADD COLUMN execution_job_id uuid,
    ADD COLUMN source_analysis_job_id uuid,
    ADD COLUMN comparison_batch_id uuid,
    ADD COLUMN job_type varchar(40),
    ADD COLUMN target_episode_nos integer[],
    ADD COLUMN diagnostic_group_key varchar(32) NOT NULL DEFAULT '';

ALTER TABLE analysis_failure_notifications DROP CONSTRAINT uq_analysis_failure_notification;
ALTER TABLE analysis_failure_notifications ADD CONSTRAINT uq_analysis_failure_notification
    UNIQUE NULLS NOT DISTINCT
        (analysis_job_id, episode_id, failure_kind, failure_code, source_transaction, diagnostic_group_key);

-- SQL/Python writer도 부가 진단 때문에 실패 저장이 거절되거나 자유 텍스트가 복사되지 않는다.
CREATE FUNCTION safe_analysis_failure_notification_diagnostics(input jsonb) RETURNS jsonb AS $$
DECLARE
    result jsonb;
    field text;
BEGIN
    IF jsonb_typeof(input) IS DISTINCT FROM 'object'
        OR jsonb_typeof(input->'schemaVersion') IS DISTINCT FROM 'number'
        OR input->>'schemaVersion' IS DISTINCT FROM '1'
        OR input->>'origin' NOT IN ('SPRING', 'PROVIDER', 'WORKER')
        OR input->>'origin' IS NULL THEN RETURN NULL; END IF;
    result := jsonb_build_object('schemaVersion', 1, 'origin', input->>'origin',
        'stage', CASE WHEN input->>'stage' IN (
            'CHARACTER_COMPARE', 'CHARACTER_CONTEXT', 'CLAIM_INPUT',
            'EXTRACTION', 'FINALIZE', 'UNKNOWN',
            'WORLD_COMPARE', 'WORLD_CONTEXT'
        ) THEN input->>'stage' ELSE 'UNKNOWN' END,
        'reasonCode', CASE WHEN input->>'reasonCode' IN (
            'COMPARISON_RESULT_INVALID', 'CURRENT_EVIDENCE_BINDING_INVALID', 'CURRENT_EVIDENCE_MANIFEST_INVALID',
            'CURRENT_EVIDENCE_OFFSET_INVALID', 'CURRENT_EVIDENCE_QUOTE_NOT_FOUND', 'CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH',
            'CURRENT_EVIDENCE_SOURCE_READ_FAILED', 'CURRENT_EVIDENCE_STRUCTURE_INVALID', 'INTERNAL_API_HTTP_ERROR',
            'INTERNAL_API_TRANSPORT_ERROR', 'LLM_OUTPUT_TRUNCATED', 'LLM_PROVIDER_ERROR',
            'LLM_RATE_LIMIT', 'LLM_TIMEOUT', 'UNKNOWN'
        ) THEN input->>'reasonCode' ELSE 'UNKNOWN' END,
        'sourceErrorCode', CASE WHEN input->>'sourceErrorCode' IN (
            'AI_TOKEN_QUOTA_EXHAUSTED', 'ANALYSIS_CURRENT_EVIDENCE_INVALID', 'ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE',
            'ANALYSIS_JOB_CHECKPOINT_INCOMPLETE', 'ANALYSIS_JOB_LEASE_CONFLICT', 'ANALYSIS_JOB_STATUS_CONFLICT',
            'ANALYSIS_RUN_PREDECESSOR_INCOMPLETE', 'ANALYSIS_RUN_STATE_CONFLICT', 'AUTH_FORBIDDEN',
            'AUTH_UNAUTHORIZED', 'COMMON_INTERNAL_SERVER_ERROR', 'CONFLICT',
            'REQUEST_INVALID_ARGUMENT', 'REQUEST_VALIDATION_FAILED', 'RESOURCE_NOT_FOUND',
            'SETTING_CANDIDATE_COMPARISON_BATCH_NOT_FOUND', 'SETTING_CANDIDATE_COMPARISON_BATCH_RESPONSE_INVALID', 'SETTING_CANDIDATE_COMPARISON_OPERATION_INVALID',
            'SETTING_CANDIDATE_COMPARISON_STALE', 'SETTING_CANDIDATE_COMPARISON_STATUS_CONFLICT', 'SETTING_CANDIDATE_COMPARISON_TARGET_INVALID',
            'SETTING_CANDIDATE_VALUE_INVALID', 'SETTING_CANDIDATE_VALUE_JSON_INVALID', 'SETTING_CANDIDATE_VALUE_TYPE_MISMATCH',
            'SETTING_CANDIDATE_WORKER_JOB_INVALID', 'UNKNOWN', 'WORLD_SETTING_CANDIDATE_COMPARISON_CONTEXT_STALE',
            'WORLD_SETTING_COMPARISON_BATCH_COMPLETION_CONFLICT', 'WORLD_SETTING_COMPARISON_BATCH_STATUS_CONFLICT', 'WORLD_SETTING_COMPARISON_TARGET_INVALID',
            'WORLD_SETTING_SUBJECT_RESOLUTION_INVALID', 'WORLD_SETTING_SUBJECT_RESOLUTION_STALE', 'WORLD_SETTING_WORKER_JOB_INVALID',
            'billing_hard_limit_reached', 'billing_not_active', 'content_filter',
            'insufficient_quota', 'invalid_request_error', 'max_output_tokens',
            'quota_exceeded', 'rate_limit_exceeded', 'server_error',
            'timeout', 'usage_limit_reached'
        ) THEN input->>'sourceErrorCode' ELSE 'UNKNOWN' END);
    IF jsonb_typeof(input->'httpStatus') = 'number' AND input->>'httpStatus' ~ '^[1-5][0-9]{2}$' THEN
        result := result || jsonb_build_object('httpStatus', (input->>'httpStatus')::integer);
    END IF;
    IF jsonb_typeof(input->'requestId') = 'string' AND (
        (input->>'origin' = 'PROVIDER' AND input->>'requestId' ~ '^[A-Za-z0-9_-]{1,128}$')
        OR (input->>'origin' <> 'PROVIDER' AND input->>'requestId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')) THEN
        result := result || jsonb_build_object('requestId', input->>'requestId');
    END IF;
    IF jsonb_typeof(input->'traceId') = 'string' AND input->>'traceId' ~ '^[0-9a-f]{32}$' THEN
        result := result || jsonb_build_object('traceId', input->>'traceId');
    END IF;
    -- 원인 연결은 저장 경계에서 검증한 값만 복사한다. 발송 때 현재 FK로 재구성하지 않는다.
    FOREACH field IN ARRAY ARRAY['sourceAnalysisJobId', 'sourceCandidateId', 'sourceEpisodeId'] LOOP
        IF input->>field ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
            result := result || jsonb_build_object(field, lower(input->>field));
        END IF;
    END LOOP;
    IF jsonb_typeof(input->'sourceEpisodeNo') = 'number' AND input->>'sourceEpisodeNo' ~ '^[0-9]{1,10}$' THEN
        IF (input->>'sourceEpisodeNo')::bigint BETWEEN 1 AND 2147483647 THEN
            result := result || jsonb_build_object('sourceEpisodeNo', (input->>'sourceEpisodeNo')::integer);
        END IF;
    END IF;
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION record_analysis_failure_notification() RETURNS TRIGGER AS $$
DECLARE
    target_job uuid;
    target_episode uuid;
    target_episode_no integer;
    target_execution uuid;
    target_source uuid;
    target_batch uuid;
    target_job_type text;
    target_episodes integer[];
    source_jobs uuid[];
    comparison_batches uuid[];
    snapshot_diagnostics jsonb;
    group_key text;
    failure_kind text;
    failure_code text;
BEGIN
    IF NEW.source_kind <> 'EPISODE' THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME = 'analysis_jobs' THEN
        IF NEW.status <> 'FAILED' THEN RETURN NEW; END IF;
        IF TG_OP = 'UPDATE' THEN
            IF OLD.status = 'FAILED' THEN RETURN NEW; END IF;
        END IF;
        target_job := NEW.id;
        target_execution := NEW.id;
        target_episode := NEW.episode_id;
        target_episode_no := NEW.source_episode_no;
        target_job_type := NEW.job_type;
        snapshot_diagnostics := safe_analysis_failure_notification_diagnostics(NEW.failure_diagnostics);
        failure_kind := 'ANALYSIS_JOB';
        failure_code := NEW.failure_code;
        IF NEW.job_type IN ('CHARACTER_FACT_COMPARISON', 'WORLD_SETTING_COMPARISON') THEN
            -- 묶음의 실제 membership을 snapshot한다. 그룹 seed를 전체 회차로 표시하지 않는다.
            WITH scope AS (
                SELECT c.episode_id, c.analysis_job_id, c.character_comparison_batch_id AS batch_id
                FROM setting_candidates c LEFT JOIN character_fact_comparison_batches b ON b.id = c.character_comparison_batch_id
                WHERE c.work_id = NEW.work_id AND NEW.job_type = 'CHARACTER_FACT_COMPARISON'
                    AND (b.analysis_job_id = NEW.id OR c.comparison_failure_analysis_job_id = NEW.id
                        OR (NEW.character_comparison_input_hash IS NULL AND c.id = NEW.setting_candidate_id))
                UNION ALL
                SELECT c.source_episode_id, c.analysis_job_id, c.comparison_batch_id
                FROM world_setting_candidates c LEFT JOIN world_setting_comparison_batches b ON b.id = c.comparison_batch_id
                WHERE c.work_id = NEW.work_id AND NEW.job_type = 'WORLD_SETTING_COMPARISON'
                    AND (b.analysis_job_id = NEW.id OR c.comparison_failure_analysis_job_id = NEW.id OR c.id = NEW.world_setting_candidate_id)
            )
            SELECT array_agg(DISTINCT e.episode_no ORDER BY e.episode_no) FILTER (WHERE e.episode_no IS NOT NULL),
                array_agg(DISTINCT s.analysis_job_id) FILTER (WHERE s.analysis_job_id IS NOT NULL),
                array_agg(DISTINCT s.batch_id) FILTER (WHERE s.batch_id IS NOT NULL)
            INTO target_episodes, source_jobs, comparison_batches
            FROM scope s LEFT JOIN episodes e ON e.id = s.episode_id;
            IF cardinality(source_jobs) = 1 THEN target_source := source_jobs[1]; END IF;
            IF cardinality(comparison_batches) = 1 THEN target_batch := comparison_batches[1]; END IF;
        ELSE
            IF NEW.job_type = 'SETTING_EXTRACTION' THEN target_source := NEW.id; END IF;
            SELECT array_agg(DISTINCT e.episode_no ORDER BY e.episode_no) INTO target_episodes
            FROM analysis_job_episode_targets t JOIN episodes e ON e.id = t.episode_id
            WHERE t.analysis_job_id = NEW.id AND e.work_id = NEW.work_id;
            IF target_episodes IS NULL AND target_episode_no IS NOT NULL THEN target_episodes := ARRAY[target_episode_no]; END IF;
        END IF;
    ELSE
        IF NEW.comparison_status IS DISTINCT FROM 'FAILED' THEN RETURN NEW; END IF;
        IF TG_OP = 'UPDATE' THEN
            IF OLD.comparison_status = 'FAILED' THEN RETURN NEW; END IF;
        END IF;
        target_job := NEW.analysis_job_id;
        IF target_job IS NULL THEN RETURN NEW; END IF;
        target_source := NEW.analysis_job_id;
        target_execution := NEW.comparison_failure_analysis_job_id;
        target_batch := NEW.comparison_failure_batch_id;
        snapshot_diagnostics := safe_analysis_failure_notification_diagnostics(NEW.comparison_failure_diagnostics);
        SELECT job_type INTO target_job_type FROM analysis_jobs WHERE id = target_execution AND work_id = NEW.work_id;
        IF TG_TABLE_NAME = 'setting_candidates' THEN
            target_episode := NEW.episode_id;
            failure_kind := 'CHARACTER_COMPARISON';
        ELSE
            target_episode := NEW.source_episode_id;
            failure_kind := 'WORLD_COMPARISON';
        END IF;
        SELECT episode_no INTO target_episode_no FROM episodes WHERE id = target_episode;
        IF target_episode_no IS NOT NULL THEN target_episodes := ARRAY[target_episode_no]; END IF;
        failure_code := NEW.comparison_failure_code;
    END IF;
    IF target_job_type NOT IN ('SETTING_EXTRACTION', 'EPISODE_VALIDATION', 'CHARACTER_FACT_COMPARISON', 'WORLD_SETTING_COMPARISON') THEN
        target_job_type := NULL;
    END IF;
    IF failure_code IS NULL OR failure_code NOT IN (
        'AI_TOKEN_QUOTA_EXHAUSTED', 'LLM_OUTPUT_TRUNCATED', 'LLM_NETWORK_ERROR',
        'LLM_PROVIDER_ERROR', 'LLM_RESPONSE_PARSE_ERROR', 'COMPARISON_VALIDATION_FAILED',
        'WORKER_LEASE_EXPIRED', 'UNEXPECTED_ERROR'
    ) THEN failure_code := 'UNEXPECTED_ERROR'; END IF;
    -- 완전한 구 writer만 기존 그룹을 쓴다. 알려진 서버 실행은 진단이 없어도 구분한다.
    group_key := CASE WHEN snapshot_diagnostics IS NULL AND target_execution IS NULL AND target_batch IS NULL THEN ''
        ELSE md5(jsonb_build_object('execution', target_execution, 'batch', target_batch, 'diagnostics', snapshot_diagnostics)::text) END;
    INSERT INTO analysis_failure_notifications
        (work_id, analysis_job_id, episode_id, episode_no, failure_kind, failure_code, source_transaction,
            failure_diagnostics, execution_job_id, source_analysis_job_id, comparison_batch_id, job_type, target_episode_nos, diagnostic_group_key)
    VALUES (NEW.work_id, target_job, target_episode, target_episode_no, failure_kind, failure_code, txid_current(),
        snapshot_diagnostics, target_execution, target_source, target_batch, target_job_type, target_episodes, group_key)
    ON CONFLICT ON CONSTRAINT uq_analysis_failure_notification
    DO UPDATE SET failure_count = analysis_failure_notifications.failure_count + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- rollback/rolling 배포의 구 writer도 새 시도에 이전 진단·실행을 가져가지 않게 한다.
CREATE FUNCTION clear_analysis_failure_attempt_diagnostics() RETURNS TRIGGER AS $$
BEGIN
    IF TG_TABLE_NAME IN ('setting_candidates', 'world_setting_candidates') THEN
        NEW.comparison_failure_diagnostics := NULL;
        NEW.comparison_failure_analysis_job_id := NULL;
        NEW.comparison_failure_batch_id := NULL;
    ELSE
        NEW.failure_diagnostics := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER analysis_job_failure_attempt_reset
    BEFORE UPDATE OF status ON analysis_jobs FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status <> 'FAILED')
    EXECUTE FUNCTION clear_analysis_failure_attempt_diagnostics();
CREATE TRIGGER character_comparison_failure_attempt_reset
    BEFORE UPDATE OF comparison_status ON setting_candidates FOR EACH ROW
    WHEN (OLD.comparison_status IS DISTINCT FROM NEW.comparison_status AND NEW.comparison_status <> 'FAILED')
    EXECUTE FUNCTION clear_analysis_failure_attempt_diagnostics();
CREATE TRIGGER world_comparison_failure_attempt_reset
    BEFORE UPDATE OF comparison_status ON world_setting_candidates FOR EACH ROW
    WHEN (OLD.comparison_status IS DISTINCT FROM NEW.comparison_status AND NEW.comparison_status <> 'FAILED')
    EXECUTE FUNCTION clear_analysis_failure_attempt_diagnostics();
CREATE TRIGGER character_batch_failure_attempt_reset
    BEFORE UPDATE OF status ON character_fact_comparison_batches FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status <> 'FAILED')
    EXECUTE FUNCTION clear_analysis_failure_attempt_diagnostics();
CREATE TRIGGER world_batch_failure_attempt_reset
    BEFORE UPDATE OF status ON world_setting_comparison_batches FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status <> 'FAILED')
    EXECUTE FUNCTION clear_analysis_failure_attempt_diagnostics();
