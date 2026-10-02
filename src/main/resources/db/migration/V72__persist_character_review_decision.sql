-- 연결 수정과 내용의 명시적 검토 결정을 구분한다. 기존 user_modified를 검토 완료로 소급 해석하지 않는다.
ALTER TABLE setting_candidates
    ADD COLUMN reviewed_application_mode varchar(30),
    ADD COLUMN reviewed_snapshot_version bigint;

ALTER TABLE setting_candidates
    ADD CONSTRAINT ck_setting_candidates_reviewed_application_mode
        CHECK (reviewed_application_mode IS NULL OR reviewed_application_mode IN ('APPLY_PROPOSAL', 'HISTORY_ONLY')),
    ADD CONSTRAINT ck_setting_candidates_reviewed_snapshot_version
        CHECK ((reviewed_application_mode IS NULL AND reviewed_snapshot_version IS NULL)
            OR (reviewed_application_mode IS NOT NULL AND reviewed_snapshot_version IS NOT NULL AND reviewed_snapshot_version >= 0));
