ALTER TABLE ai_token_extension_requests
    ADD COLUMN request_source VARCHAR(40) NOT NULL DEFAULT 'QUOTA_EXHAUSTION';

ALTER TABLE ai_token_extension_requests
    DROP CONSTRAINT chk_ai_token_extension_requests_context,
    ADD CONSTRAINT chk_ai_token_extension_requests_context
        CHECK (request_context IN (
            'REQUEST_BLOCKED',
            'ANALYSIS_FAILED',
            'ANALYSIS_INTERRUPTED',
            'GENERAL_FEEDBACK'
        )),
    ADD CONSTRAINT chk_ai_token_extension_requests_source
        CHECK (request_source IN ('QUOTA_EXHAUSTION', 'GENERAL_FEEDBACK_REWARD')),
    ADD CONSTRAINT chk_ai_token_extension_requests_source_context
        CHECK (
            (request_source = 'QUOTA_EXHAUSTION'
                AND request_context <> 'GENERAL_FEEDBACK')
            OR (request_source = 'GENERAL_FEEDBACK_REWARD'
                AND request_context = 'GENERAL_FEEDBACK')
        );

CREATE UNIQUE INDEX uk_ai_token_extension_requests_member_feedback_reward
    ON ai_token_extension_requests (member_id)
    WHERE request_source = 'GENERAL_FEEDBACK_REWARD';

CREATE TABLE feedbacks (
    id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    page_path VARCHAR(255),
    reward_request_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_feedbacks_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT fk_feedbacks_reward_request
        FOREIGN KEY (reward_request_id)
        REFERENCES ai_token_extension_requests (id)
        ON DELETE SET NULL,
    CONSTRAINT chk_feedbacks_content
        CHECK (CHAR_LENGTH(BTRIM(content)) BETWEEN 35 AND 1000),
    CONSTRAINT chk_feedbacks_page_path
        CHECK (
            page_path IS NULL
            OR (
                CHAR_LENGTH(page_path) BETWEEN 1 AND 255
                AND page_path LIKE '/%'
                AND POSITION('?' IN page_path) = 0
                AND POSITION('#' IN page_path) = 0
            )
        )
);

CREATE INDEX idx_feedbacks_member_created
    ON feedbacks (member_id, created_at DESC);
