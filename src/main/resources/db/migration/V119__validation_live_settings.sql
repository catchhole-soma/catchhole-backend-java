-- #381 검수는 접수 때 확정 설정을 복사하지 않는다. 판정 입력은 현재 DB 설정을 읽고, 검수가 진행되는 동안의 일관성은 확정 설정 잠금이 보장한다.
-- V119 이후 접수한 report는 baseline_seed·baseline_entries가 NULL이고 baseline_hash에 설정 버전 fingerprint를 둔다.
-- 이미 seed가 있는 report(legacy)는 그대로 읽는다. 두 열은 마지막 legacy report가 정리된 뒤 별도 migration에서 지운다.
ALTER TABLE validation_reports DROP CONSTRAINT ck_validation_reports_ready_baseline;
ALTER TABLE validation_reports ADD CONSTRAINT ck_validation_reports_ready_baseline_v2
    CHECK (baseline_status <> 'READY' OR baseline_hash IS NOT NULL);

-- 판정 근거 문장(B 인용)이 가리킨 주체·설정의 이름표(최대 3개). 이름·key만 담고 설정값은 담지 않는다. 게시 때 한 번 쓰며 legacy finding은 NULL이다.
ALTER TABLE validation_findings ADD COLUMN quoted_subjects JSONB;

COMMENT ON COLUMN validation_reports.baseline_seed IS 'Legacy (#381): V119 이후 접수한 report는 NULL. 판정 입력은 현재 확정 설정을 읽는다.';
COMMENT ON COLUMN validation_reports.baseline_entries IS 'Legacy (#381): V119 이후 접수한 report는 NULL. seed가 있는 legacy report만 읽는다.';
COMMENT ON COLUMN validation_reports.baseline_hash IS '#381: seed가 없는 report는 설정 버전 fingerprint(ACTIVE 인물 id·snapshotVersion, 세계관 id·version의 hash). legacy report는 seed hash.';
COMMENT ON COLUMN validation_reports.confirmed_state_hash IS '#381: seed가 없는 report는 baseline_hash와 같은 설정 버전 fingerprint. legacy report는 접수 당시 확정 상태 전체의 hash.';
COMMENT ON COLUMN validation_findings.quoted_subjects IS '#381: B 인용별 {ref, subjectType, subjectId, subjectName, settingKey, settingName} 최대 3개. 설정값은 저장하지 않는다. legacy finding은 NULL.';
