CREATE TABLE ai_token_extension_requests (
    id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    feedback VARCHAR(1000) NOT NULL,
    request_context VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reviewed_by_member_id BIGINT,
    reviewed_at TIMESTAMP,
    granted_amount BIGINT,
    rejection_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_token_extension_requests_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_token_extension_requests_feedback
        CHECK (CHAR_LENGTH(BTRIM(feedback)) BETWEEN 50 AND 1000),
    CONSTRAINT chk_ai_token_extension_requests_context
        CHECK (request_context IN ('REQUEST_BLOCKED', 'ANALYSIS_FAILED', 'ANALYSIS_INTERRUPTED')),
    CONSTRAINT chk_ai_token_extension_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_ai_token_extension_requests_lifecycle
        CHECK (
            (status = 'PENDING'
                AND reviewed_by_member_id IS NULL
                AND reviewed_at IS NULL
                AND granted_amount IS NULL
                AND rejection_reason IS NULL)
            OR (status = 'APPROVED'
                AND reviewed_by_member_id IS NOT NULL
                AND reviewed_at IS NOT NULL
                AND granted_amount > 0
                AND rejection_reason IS NULL)
            OR (status = 'REJECTED'
                AND reviewed_by_member_id IS NOT NULL
                AND reviewed_at IS NOT NULL
                AND granted_amount IS NULL
                AND CHAR_LENGTH(BTRIM(rejection_reason)) BETWEEN 1 AND 500)
        )
);

CREATE UNIQUE INDEX uk_ai_token_extension_requests_member_pending
    ON ai_token_extension_requests (member_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_ai_token_extension_requests_status_created
    ON ai_token_extension_requests (status, created_at ASC);

ALTER TABLE ai_token_grants
    ADD COLUMN extension_request_id UUID,
    ADD CONSTRAINT fk_ai_token_grants_extension_request
        FOREIGN KEY (extension_request_id)
        REFERENCES ai_token_extension_requests (id)
        ON DELETE SET NULL,
    ADD CONSTRAINT uk_ai_token_grants_extension_request
        UNIQUE (extension_request_id);
