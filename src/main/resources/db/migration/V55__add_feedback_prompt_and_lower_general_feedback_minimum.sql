ALTER TABLE members
    ADD COLUMN feedback_prompt_shown_at TIMESTAMP;

ALTER TABLE feedbacks
    DROP CONSTRAINT chk_feedbacks_content,
    ADD CONSTRAINT chk_feedbacks_content
        CHECK (CHAR_LENGTH(BTRIM(content)) BETWEEN 10 AND 1000);

ALTER TABLE ai_token_extension_requests
    DROP CONSTRAINT chk_ai_token_extension_requests_feedback,
    ADD CONSTRAINT chk_ai_token_extension_requests_feedback
        CHECK (
            (request_source = 'GENERAL_FEEDBACK_REWARD'
                AND CHAR_LENGTH(BTRIM(feedback)) BETWEEN 10 AND 1000)
            OR (request_source = 'QUOTA_EXHAUSTION'
                AND CHAR_LENGTH(BTRIM(feedback)) BETWEEN 35 AND 1000)
        );
