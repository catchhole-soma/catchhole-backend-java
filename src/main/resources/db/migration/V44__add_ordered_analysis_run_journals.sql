ALTER TABLE analysis_jobs
    ADD COLUMN analysis_mode VARCHAR(30) NOT NULL DEFAULT 'CONFIRMED_ONLY',
    ADD COLUMN analysis_run_id UUID,
    ADD COLUMN run_generation BIGINT,
    ADD COLUMN run_sequence INTEGER,
    ADD COLUMN predecessor_job_id UUID,
    ADD COLUMN run_base_state JSONB,
    ADD COLUMN input_state_hash VARCHAR(64),
    ADD COLUMN source_content_hash VARCHAR(64),
    ADD COLUMN source_content_s3_key TEXT,
    ADD COLUMN source_content_s3_version VARCHAR(255),
    ADD COLUMN state_journal JSONB,
    ADD COLUMN journal_status VARCHAR(30),
    ADD COLUMN journal_invalidation_reason VARCHAR(200);

ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_mode
    CHECK (analysis_mode IN ('CONFIRMED_ONLY', 'ORDERED_PROVISIONAL'));
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_journal_status
    CHECK (journal_status IS NULL OR journal_status IN ('PENDING', 'SEALED', 'INCOMPLETE', 'INVALIDATED'));
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_ordered_run
    CHECK (
        (analysis_mode = 'CONFIRMED_ONLY' AND analysis_run_id IS NULL AND run_generation IS NULL
            AND run_sequence IS NULL AND predecessor_job_id IS NULL AND run_base_state IS NULL
            AND input_state_hash IS NULL AND state_journal IS NULL AND journal_status IS NULL)
        OR
        (analysis_mode = 'ORDERED_PROVISIONAL' AND job_type = 'SETTING_EXTRACTION'
            AND episode_id IS NOT NULL AND analysis_run_id IS NOT NULL AND run_generation >= 1
            AND run_generation IS NOT NULL AND run_sequence >= 0 AND run_sequence IS NOT NULL
            AND source_content_hash IS NOT NULL AND source_content_hash ~ '^[0-9a-f]{64}$'
            AND source_content_s3_key IS NOT NULL AND journal_status IS NOT NULL
            AND ((run_sequence = 0 AND predecessor_job_id IS NULL AND run_base_state IS NOT NULL
                    AND jsonb_typeof(run_base_state) = 'object')
                OR (run_sequence > 0 AND predecessor_job_id IS NOT NULL AND run_base_state IS NULL)))
    );
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_sealed_journal
    CHECK (journal_status <> 'SEALED' OR (state_journal IS NOT NULL AND input_state_hash IS NOT NULL
        AND jsonb_typeof(state_journal -> 'changes') = 'array'
        AND state_journal ->> 'outputStateHash' IS NOT NULL));

CREATE UNIQUE INDEX uq_analysis_jobs_run_sequence
    ON analysis_jobs (analysis_run_id, run_generation, run_sequence)
    WHERE analysis_run_id IS NOT NULL;
CREATE INDEX idx_analysis_jobs_work_claim
    ON analysis_jobs (work_id, status, analysis_mode, created_at);
CREATE INDEX idx_analysis_jobs_predecessor ON analysis_jobs (predecessor_job_id)
    WHERE predecessor_job_id IS NOT NULL;

COMMENT ON COLUMN analysis_jobs.run_base_state IS '첫 회차 Job에 고정한 확정 설정 S0. 후속 상태는 검증된 변경 기록으로 복원한다.';
COMMENT ON COLUMN analysis_jobs.state_journal IS '당시 검증한 제안값과 출처를 보존한 변경 기록. 후보 현재값에 의존하지 않는다.';
COMMENT ON COLUMN analysis_jobs.predecessor_job_id IS '동일 실행·generation의 직전 Job. 실행 경계를 서비스에서 검증하며 작품 일괄 삭제와 순환 FK를 피한다.';
