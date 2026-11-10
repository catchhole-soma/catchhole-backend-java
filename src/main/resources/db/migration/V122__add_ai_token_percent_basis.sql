-- 남은 사용량 비율의 100% 기준을 계정마다 보관한다. 지급받은 순간의 잔량이 기준이다.
-- 과거 지급 시점의 잔량은 복원할 수 없으므로 마지막 지급량과 현재 잔량 중 큰 값으로 시작한다.
-- DEFAULT 0은 이 컬럼을 모르는 이전 이미지로 되돌렸을 때도 계정 생성이 실패하지 않게 둔다.
ALTER TABLE ai_token_accounts
    ADD COLUMN percent_basis_tokens NUMERIC(22, 2) NOT NULL DEFAULT 0;

UPDATE ai_token_accounts account
SET percent_basis_tokens = GREATEST(
        COALESCE((
            SELECT grant_row.amount
            FROM ai_token_grants grant_row
            WHERE grant_row.member_id = account.member_id
            ORDER BY grant_row.created_at DESC, grant_row.id DESC
            LIMIT 1
        ), 0),
        GREATEST(account.granted_tokens - account.used_tokens - account.reserved_tokens, 0)
    );

ALTER TABLE ai_token_accounts
    ADD CONSTRAINT chk_ai_token_accounts_percent_basis_non_negative CHECK (percent_basis_tokens >= 0);
