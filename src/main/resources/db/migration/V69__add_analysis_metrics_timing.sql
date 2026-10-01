-- 과거 접수/결과는 시간과 counter를 추정하지 않는다. explicit retry부터 새 측정을 시작한다.
ALTER TABLE analysis_jobs
    ADD COLUMN attempt_requested_at TIMESTAMP,
    ADD COLUMN pending_since TIMESTAMP,
    ADD COLUMN metrics_attempt_no INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN result_ready_at TIMESTAMP,
    ADD COLUMN result_outcome VARCHAR(20),
    ADD COLUMN metrics_user_retry BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE setting_candidates
    ADD COLUMN comparison_terminal_at TIMESTAMP,
    ADD COLUMN comparison_terminal_outcome VARCHAR(20),
    ADD COLUMN metrics_source_attempt_no INTEGER NOT NULL DEFAULT 0;
ALTER TABLE world_setting_candidates
    ADD COLUMN comparison_terminal_at TIMESTAMP,
    ADD COLUMN comparison_terminal_outcome VARCHAR(20),
    ADD COLUMN metrics_source_attempt_no INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_analysis_jobs_metrics_unresolved ON analysis_jobs (id)
    WHERE attempt_requested_at IS NOT NULL AND result_ready_at IS NULL AND status = 'SUCCEEDED';
