-- GH359: V107–V110의 실행별 발송·검사·발췌 정제를 유지하며 부분 결과를 추가한다.
CREATE OR REPLACE FUNCTION safe_analysis_failure_notification_diagnostics(input jsonb) RETURNS jsonb AS $$
DECLARE
    result jsonb;
    field text;
    checks jsonb;
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
            'RELATIONSHIP_EXTRACTION', 'RELATIONSHIP_COMPARE', 'RELATIONSHIP_RESUME',
            'WORLD_COMPARE', 'WORLD_CONTEXT'
        ) THEN input->>'stage' ELSE 'UNKNOWN' END,
        'reasonCode', CASE WHEN input->>'reasonCode' IN (
            'COMPARISON_RESULT_INVALID', 'CURRENT_EVIDENCE_BINDING_INVALID', 'CURRENT_EVIDENCE_MANIFEST_INVALID',
            'CURRENT_EVIDENCE_OFFSET_INVALID', 'CURRENT_EVIDENCE_QUOTE_NOT_FOUND', 'CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH',
            'CURRENT_EVIDENCE_SOURCE_READ_FAILED', 'CURRENT_EVIDENCE_STRUCTURE_INVALID', 'INTERNAL_API_HTTP_ERROR',
            'INTERNAL_API_TRANSPORT_ERROR', 'LLM_OUTPUT_TRUNCATED', 'LLM_PROVIDER_ERROR',
            'LLM_RATE_LIMIT', 'LLM_TIMEOUT', 'UNKNOWN',
            'RELATIONSHIP_OBSERVATION_REFS_INVALID',
            'RELATIONSHIP_OBSERVATION_ORDER_INVALID',
            'RELATIONSHIP_DISPOSITION_CONFLICT',
            'RELATIONSHIP_DISPLAY_REFERENCE_LEAK',
            'RELATIONSHIP_DISPOSITION_CONTENT_INVALID',
            'RELATIONSHIP_NONPRESENT_MUTATION',
            'RELATIONSHIP_MUTATION_CONTENT_MISSING',
            'RELATIONSHIP_SELF_RELATIONSHIP',
            'RELATIONSHIP_ENDPOINTS_UNGROUNDED',
            'RELATIONSHIP_DUPLICATE_SLOT',
            'RELATIONSHIP_ADD_SLOT_EXISTS',
            'RELATIONSHIP_EXISTING_REF_MISMATCH',
            'RELATIONSHIP_DUPLICATE_TARGET',
            'RELATIONSHIP_INACTIVE_TARGET',
            'RELATIONSHIP_MERGE_CONTENT_CHANGED',
            'RELATIONSHIP_OBSERVATION_COVERAGE_MISSING',
            'RELATIONSHIP_RESPONSE_JSON_INVALID',
            'RELATIONSHIP_RESPONSE_SCHEMA_INVALID',
            'RELATIONSHIP_INPUT_TOO_LARGE',
            'RELATIONSHIP_CONTEXT_INVALID', 'RELATIONSHIP_PREVIOUS_FAILURE', 'RELATIONSHIP_PREDECESSOR_INCOMPLETE',
            'WORLD_SUBJECT_RESOLUTION_FAILED', 'WORLD_COMPARISON_INPUT_TOO_LARGE',
            'WORLD_SUBJECT_COVERAGE_INVALID', 'WORLD_SUBJECT_REFERENCE_INVALID', 'WORLD_SUBJECT_RESPONSE_INVALID'
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
    checks := safe_analysis_failure_checks(input->'checks');
    IF checks <> '[]'::jsonb THEN result := result || jsonb_build_object('checks', checks); END IF;
    IF jsonb_typeof(input->'subjectName') = 'string' AND input->>'subjectName' ~ '[^[:space:]]' THEN
        result := result || jsonb_build_object('subjectName', left(input->>'subjectName', 60));
    END IF;
    checks := safe_analysis_failure_excerpts(input->'excerpts');
    IF checks <> '[]'::jsonb THEN result := result || jsonb_build_object('excerpts', checks); END IF;
    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

ALTER TABLE analysis_jobs ADD COLUMN relationship_partial_results_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE relationship_graph_analyses ADD COLUMN failures jsonb;
ALTER TABLE relationship_graph_analyses ADD CONSTRAINT ck_relationship_group_failures
    CHECK (failures IS NULL OR jsonb_typeof(failures) = 'array');
ALTER TABLE relationship_backfill_items DROP CONSTRAINT relationship_backfill_items_state_check;
ALTER TABLE relationship_backfill_items ADD CONSTRAINT relationship_backfill_items_state_check
    CHECK (state IN ('WAITING','RUNNING','SUCCEEDED','PARTIAL','FAILED','SKIPPED'));
CREATE INDEX idx_relationship_partial_prefix ON relationship_graph_analyses(work_id,generation,episode_no)
    WHERE status = 'APPLIED' AND failure_code = 'PARTIAL_COMPARISON_FAILED';

-- A successfully committed partial graph is not a failed extraction Job. Snapshot each safe
-- comparison reason once, without raw model output, names or excerpts. Propagated gaps do not alert.
CREATE FUNCTION record_relationship_partial_notification() RETURNS TRIGGER AS $$
DECLARE
    reason record;
    diagnostic jsonb;
    owning_job analysis_jobs%ROWTYPE;
BEGIN
    IF NEW.status <> 'APPLIED' OR jsonb_typeof(NEW.failures) IS DISTINCT FROM 'array' THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'APPLIED' THEN RETURN NEW; END IF;
    SELECT * INTO owning_job FROM analysis_jobs WHERE id = NEW.id AND work_id = NEW.work_id
        AND episode_id = NEW.episode_id AND source_kind = 'EPISODE';
    IF NOT FOUND OR owning_job.job_type NOT IN ('SETTING_EXTRACTION','RELATIONSHIP_EXTRACTION') THEN RETURN NEW; END IF;
    FOR reason IN
        SELECT value->>'reasonCode' AS code, count(*)::integer AS groups
        FROM jsonb_array_elements(NEW.failures)
        WHERE value->>'reasonCode' IN (
            'RELATIONSHIP_OBSERVATION_REFS_INVALID','RELATIONSHIP_OBSERVATION_ORDER_INVALID',
            'RELATIONSHIP_DISPOSITION_CONFLICT','RELATIONSHIP_DISPLAY_REFERENCE_LEAK',
            'RELATIONSHIP_DISPOSITION_CONTENT_INVALID','RELATIONSHIP_NONPRESENT_MUTATION',
            'RELATIONSHIP_MUTATION_CONTENT_MISSING','RELATIONSHIP_SELF_RELATIONSHIP',
            'RELATIONSHIP_ENDPOINTS_UNGROUNDED','RELATIONSHIP_DUPLICATE_SLOT',
            'RELATIONSHIP_ADD_SLOT_EXISTS','RELATIONSHIP_EXISTING_REF_MISMATCH',
            'RELATIONSHIP_DUPLICATE_TARGET','RELATIONSHIP_INACTIVE_TARGET',
            'RELATIONSHIP_MERGE_CONTENT_CHANGED','RELATIONSHIP_OBSERVATION_COVERAGE_MISSING',
            'RELATIONSHIP_RESPONSE_JSON_INVALID','RELATIONSHIP_RESPONSE_SCHEMA_INVALID',
            'RELATIONSHIP_INPUT_TOO_LARGE')
        GROUP BY value->>'reasonCode'
    LOOP
        diagnostic := safe_analysis_failure_notification_diagnostics(jsonb_build_object(
            'schemaVersion',1,'origin','WORKER','stage','RELATIONSHIP_COMPARE','reasonCode',reason.code));
        INSERT INTO analysis_failure_notifications
            (work_id,analysis_job_id,episode_id,episode_no,failure_kind,failure_code,failure_count,
             source_transaction,failure_diagnostics,execution_job_id,source_analysis_job_id,
             job_type,target_episode_nos,diagnostic_group_key,execution_attempt_id)
        VALUES (NEW.work_id,NEW.id,NEW.episode_id,NEW.episode_no,'RELATIONSHIP_COMPARISON',
            'COMPARISON_VALIDATION_FAILED',reason.groups,txid_current(),diagnostic,NEW.id,
            CASE WHEN owning_job.job_type = 'SETTING_EXTRACTION' THEN NEW.id ELSE NULL END,
            owning_job.job_type,ARRAY[NEW.episode_no],
            md5(jsonb_build_object('attempt',owning_job.failure_notification_attempt_id,'diagnostics',diagnostic)::text),
            owning_job.failure_notification_attempt_id)
        ON CONFLICT ON CONSTRAINT uq_analysis_failure_notification DO NOTHING;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER relationship_partial_notification AFTER INSERT OR UPDATE OF status
    ON relationship_graph_analyses FOR EACH ROW EXECUTE FUNCTION record_relationship_partial_notification();
