-- 기존 지급·사용·예약량은 환산하지 않는다. 새 예약부터 모델별 사용자 차감 정책을 고정한다.
ALTER TABLE ai_token_accounts
    ALTER COLUMN granted_tokens TYPE NUMERIC(22, 2) USING granted_tokens::NUMERIC(22, 2),
    ALTER COLUMN used_tokens TYPE NUMERIC(22, 2) USING used_tokens::NUMERIC(22, 2),
    ALTER COLUMN reserved_tokens TYPE NUMERIC(22, 2) USING reserved_tokens::NUMERIC(22, 2);

ALTER TABLE ai_token_usages
    ADD COLUMN charge_policy_version VARCHAR(40),
    ADD COLUMN charge_denominator INTEGER,
    ADD COLUMN reserved_charge NUMERIC(22, 2),
    ADD COLUMN charged_tokens NUMERIC(22, 2);

-- 진행 중·UNKNOWN 사용량도 당시의 1:1 정책을 보존하여 늦은 정산에 새 할인을 적용하지 않는다.
UPDATE ai_token_usages
SET charge_policy_version = 'LEGACY_RAW_V1',
    charge_denominator = 1,
    reserved_charge = reserved_tokens::NUMERIC(22, 2),
    charged_tokens = CASE WHEN status = 'SETTLED'
        THEN input_tokens::NUMERIC + output_tokens::NUMERIC
        ELSE NULL END;

-- 운영 백필은 원시 사용량만 기록하고 사용자 예약·차감량은 과거에도 현재에도 0이다.
UPDATE ai_token_usages usage
SET charge_policy_version = 'SERVICE_PAID_V1',
    reserved_charge = 0,
    charged_tokens = CASE WHEN usage.status = 'SETTLED' THEN 0 ELSE NULL END
FROM chat_runs run
WHERE usage.chat_run_id = run.id AND run.question_id IS NULL;

-- 같은 행의 raw 예약량을 DEFAULT로 복사할 수 없으므로 구버전 writer를 허용하는 0 기본값을 두지 않는다.
ALTER TABLE ai_token_usages
    ALTER COLUMN charge_policy_version SET NOT NULL,
    ALTER COLUMN charge_denominator SET NOT NULL,
    ALTER COLUMN reserved_charge SET NOT NULL,
    ADD CONSTRAINT ck_ai_token_usages_charge_denominator CHECK (charge_denominator IN (1, 20)),
    ADD CONSTRAINT ck_ai_token_usages_charge_non_negative
        CHECK (reserved_charge >= 0 AND (charged_tokens IS NULL OR charged_tokens >= 0));

COMMENT ON COLUMN ai_token_usages.charge_policy_version IS '예약 당시 고정한 사용자 차감 정책. 기존 예약은 LEGACY_RAW_V1, 운영 백필은 SERVICE_PAID_V1.';
COMMENT ON COLUMN ai_token_usages.charge_denominator IS '사용자 차감 환산 분모. 실제 provider 사용량과 실행 예산은 raw token 컬럼에 보존한다.';
COMMENT ON COLUMN ai_token_usages.reserved_charge IS '예약 당시 사용자 계정에 확보한 차감량. 서비스 부담 호출은 0.';
COMMENT ON COLUMN ai_token_usages.charged_tokens IS '정산된 사용자 차감량. 미정산·UNKNOWN은 NULL이며 서비스 부담 정산은 0.';
