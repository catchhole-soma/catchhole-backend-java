-- #349 검수 개편(grid v4): 역할별 cap을 작가 [다시 시도] 한 번(실행 한 번) 기준으로 센다.
-- run_start_generation은 현재 실행의 첫 generation이다. 공개 retry가 새 generation으로 옮기고,
-- Worker lease 회수(expire)는 같은 실행을 이어 가므로 바꾸지 않는다.
-- 기존 Job은 1로 시작해 지금까지의 사용량을 모두 같은 실행으로 센다(배포 전 동작과 같다).
ALTER TABLE validation_jobs ADD COLUMN run_start_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE validation_jobs ADD CONSTRAINT ck_validation_jobs_run_start_generation
    CHECK (run_start_generation >= 1 AND run_start_generation <= generation);
