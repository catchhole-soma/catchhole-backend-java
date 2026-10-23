-- 실패 상태와 발송 대기를 함께 커밋한다. Python의 최초 후보 저장과 SQL 갱신도 포함한다.
-- 원문/오류 메시지/회원 연락처는 저장하지 않는다. 작품 파기 시 발송 대기도 함께 삭제한다.
CREATE TABLE analysis_failure_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    analysis_job_id UUID NOT NULL,
    episode_id UUID,
    episode_no INTEGER,
    failure_kind VARCHAR(40) NOT NULL,
    failure_code VARCHAR(60) NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 1,
    source_transaction BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT statement_timestamp(),
    available_at TIMESTAMP NOT NULL DEFAULT statement_timestamp(),
    lease_token UUID,
    attempts INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMP,
    CONSTRAINT uq_analysis_failure_notification UNIQUE NULLS NOT DISTINCT
        (analysis_job_id, episode_id, failure_kind, failure_code, source_transaction)
);
CREATE INDEX idx_analysis_failure_notification_pending
    ON analysis_failure_notifications(available_at, occurred_at) WHERE delivered_at IS NULL;

CREATE FUNCTION record_analysis_failure_notification() RETURNS TRIGGER AS $$
DECLARE
    target_job UUID;
    target_episode UUID;
    target_episode_no INTEGER;
    failure_kind TEXT;
    failure_code TEXT;
BEGIN
    IF NEW.source_kind <> 'EPISODE' THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME = 'analysis_jobs' THEN
        IF NEW.status <> 'FAILED' THEN RETURN NEW; END IF;
        IF TG_OP = 'UPDATE' THEN
            IF OLD.status = 'FAILED' THEN RETURN NEW; END IF;
        END IF;
        target_job := NEW.id;
        target_episode := NEW.episode_id;
        target_episode_no := NEW.source_episode_no;
        failure_kind := 'ANALYSIS_JOB';
        failure_code := NEW.failure_code;
    ELSE
        IF NEW.comparison_status IS DISTINCT FROM 'FAILED' THEN RETURN NEW; END IF;
        IF TG_OP = 'UPDATE' THEN
            IF OLD.comparison_status = 'FAILED' THEN RETURN NEW; END IF;
        END IF;
        target_job := NEW.analysis_job_id;
        IF target_job IS NULL THEN RETURN NEW; END IF;
        IF TG_TABLE_NAME = 'setting_candidates' THEN
            target_episode := NEW.episode_id;
            failure_kind := 'CHARACTER_COMPARISON';
        ELSE
            target_episode := NEW.source_episode_id;
            failure_kind := 'WORLD_COMPARISON';
        END IF;
        SELECT episode_no INTO target_episode_no FROM episodes WHERE id = target_episode;
        failure_code := NEW.comparison_failure_code;
    END IF;
    -- 알려진 코드만 외부 알림에 보관한다. 자유 형식 오류는 복사하지 않는다.
    IF failure_code IS NULL OR failure_code NOT IN (
        'AI_TOKEN_QUOTA_EXHAUSTED', 'LLM_OUTPUT_TRUNCATED', 'LLM_NETWORK_ERROR',
        'LLM_PROVIDER_ERROR', 'LLM_RESPONSE_PARSE_ERROR', 'COMPARISON_VALIDATION_FAILED',
        'WORKER_LEASE_EXPIRED', 'UNEXPECTED_ERROR'
    ) THEN failure_code := 'UNEXPECTED_ERROR'; END IF;
    INSERT INTO analysis_failure_notifications
        (work_id, analysis_job_id, episode_id, episode_no, failure_kind, failure_code, source_transaction)
    VALUES (NEW.work_id, target_job, target_episode, target_episode_no, failure_kind, failure_code, txid_current())
    ON CONFLICT ON CONSTRAINT uq_analysis_failure_notification
    DO UPDATE SET failure_count = analysis_failure_notifications.failure_count + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER analysis_job_failure_notification
    AFTER INSERT OR UPDATE OF status ON analysis_jobs
    FOR EACH ROW EXECUTE FUNCTION record_analysis_failure_notification();
CREATE TRIGGER character_comparison_failure_notification
    AFTER INSERT OR UPDATE OF comparison_status ON setting_candidates
    FOR EACH ROW EXECUTE FUNCTION record_analysis_failure_notification();
CREATE TRIGGER world_comparison_failure_notification
    AFTER INSERT OR UPDATE OF comparison_status ON world_setting_candidates
    FOR EACH ROW EXECUTE FUNCTION record_analysis_failure_notification();
