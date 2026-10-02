ALTER TABLE analysis_jobs
    ADD COLUMN metrics_request_id UUID,
    ADD COLUMN metrics_request_started_at TIMESTAMP,
    ADD COLUMN metrics_request_episode_count INTEGER;

-- 공통 run과 최초 접수 시각이 온전히 남은 누적 실행만 복원한다. 업로드 batch로 요청을 추정하지 않는다.
WITH recoverable_runs AS (
    SELECT analysis_run_id, min(attempt_requested_at) AS requested_at, count(*)::INTEGER AS episode_count
    FROM analysis_jobs
    WHERE analysis_run_id IS NOT NULL
    GROUP BY analysis_run_id
    HAVING count(attempt_requested_at) = count(*)
       AND min(metrics_attempt_no) = 1 AND max(metrics_attempt_no) = 1
       AND count(run_generation) = count(*) AND min(run_generation) = max(run_generation)
       AND count(episode_id) = count(*) AND count(DISTINCT episode_id) = count(*)
       AND count(DISTINCT run_sequence) = count(*)
       AND min(run_sequence) = 0 AND max(run_sequence) = count(*) - 1
       AND sum(CASE WHEN job_type = 'SETTING_EXTRACTION' THEN 1 ELSE 0 END) = count(*)
)
UPDATE analysis_jobs job
SET metrics_request_id = recovered.analysis_run_id,
    metrics_request_started_at = recovered.requested_at,
    metrics_request_episode_count = recovered.episode_count
FROM recoverable_runs recovered
WHERE job.analysis_run_id = recovered.analysis_run_id;

CREATE INDEX idx_analysis_jobs_metrics_request ON analysis_jobs
    (metrics_request_id, episode_id, job_type, created_at DESC, id DESC)
    WHERE metrics_request_id IS NOT NULL;
