-- 기존 user_modified는 연결 수정도 포함하므로 기존 행의 내용 수정 여부를 추정하지 않는다.
ALTER TABLE setting_candidates ADD COLUMN user_content_modified boolean;

-- 신규 Python Worker INSERT는 이 컬럼을 생략한다. 기존 행은 NULL로 남겨 보수적으로 보호한다.
ALTER TABLE setting_candidates ALTER COLUMN user_content_modified SET DEFAULT false;
