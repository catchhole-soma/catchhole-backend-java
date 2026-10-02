-- 서비스 이용 현황은 최초 생성과 명시적 사용자 재시도를 기간 조회한다.
-- lease 회수는 기존 접수 시각을 유지하므로 새 이용 활동으로 세지 않는다.
CREATE INDEX idx_analysis_jobs_usage_created
    ON analysis_jobs (created_at, work_id)
    WHERE job_type IN ('SETTING_EXTRACTION', 'EPISODE_VALIDATION');

CREATE INDEX idx_analysis_jobs_usage_attempt_requested
    ON analysis_jobs (attempt_requested_at, work_id)
    WHERE job_type IN ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
      AND attempt_requested_at IS NOT NULL;

-- V70의 요청 ID 인덱스는 같은 묶음의 전체 원본을 검증할 때 재사용한다.
CREATE INDEX idx_analysis_jobs_usage_request_started
    ON analysis_jobs (metrics_request_started_at, metrics_request_id)
    WHERE job_type IN ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
      AND metrics_request_id IS NOT NULL AND metrics_request_started_at IS NOT NULL;
