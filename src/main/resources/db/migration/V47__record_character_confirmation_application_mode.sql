ALTER TABLE setting_candidates ADD COLUMN confirmed_application_mode VARCHAR(30);
ALTER TABLE setting_candidates ADD CONSTRAINT ck_setting_candidates_confirmed_application_mode
    CHECK (confirmed_application_mode IS NULL
        OR confirmed_application_mode IN ('APPLY_PROPOSAL', 'HISTORY_ONLY'));
COMMENT ON COLUMN setting_candidates.confirmed_application_mode IS '사용자 확정 시 제안을 실제 설정에 반영했는지 기록. legacy NULL은 후속 임시 의존성의 반영 증거로 사용하지 않는다.';
