ALTER TABLE setting_candidates ADD COLUMN user_modified BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN setting_candidates.user_modified IS '사용자 내용·대상 수정 또는 반려 기록. AI 제안/자동 제외와 구분하고 명시적인 수정값 적용 검증에 사용한다.';
