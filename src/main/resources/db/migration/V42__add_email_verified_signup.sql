-- 기존 회원의 이메일 소유 확인 이력은 추정하지 않는다.
ALTER TABLE members ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE members ALTER COLUMN phone_number DROP NOT NULL;
-- NULL 전화번호는 여러 회원이 가질 수 있지만 실제 등록 번호의 유일성은 유지한다.
