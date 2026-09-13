ALTER TABLE analysis_jobs
    ADD COLUMN review_mode VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN automatic_input_state JSONB,
    ADD COLUMN automatic_applied_at TIMESTAMP;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_review_mode
    CHECK (review_mode IN ('MANUAL', 'AUTOMATIC'));
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_automatic_application
    CHECK (automatic_applied_at IS NULL OR review_mode = 'AUTOMATIC');

ALTER TABLE setting_candidates ADD COLUMN reviewed_automatically BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE world_setting_candidates ADD COLUMN reviewed_automatically BOOLEAN NOT NULL DEFAULT FALSE;

-- 단일 분석의 10회차 집계도 현재 원문에 대한 성공인지 검사할 수 있도록 생성 당시 회차를 보존한다.
-- 과거 단일 Job의 NULL 출처는 그대로 유지한다.
ALTER TABLE analysis_jobs DROP CONSTRAINT ck_analysis_jobs_source_episode_no;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_source_episode_no
    CHECK ((analysis_mode = 'CONFIRMED_ONLY' AND (source_episode_no IS NULL OR source_episode_no > 0))
        OR (analysis_mode = 'ORDERED_PROVISIONAL' AND source_episode_no IS NOT NULL AND source_episode_no > 0));
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_automatic_mode
    CHECK (review_mode <> 'AUTOMATIC' OR (analysis_mode = 'ORDERED_PROVISIONAL' AND job_type = 'SETTING_EXTRACTION'));
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_automatic_input
    CHECK (automatic_input_state IS NULL OR (review_mode = 'AUTOMATIC' AND jsonb_typeof(automatic_input_state) = 'object'));
