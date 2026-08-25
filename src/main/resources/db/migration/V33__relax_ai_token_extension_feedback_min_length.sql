ALTER TABLE ai_token_extension_requests
    DROP CONSTRAINT chk_ai_token_extension_requests_feedback,
    ADD CONSTRAINT chk_ai_token_extension_requests_feedback
        CHECK (CHAR_LENGTH(BTRIM(feedback)) BETWEEN 35 AND 1000);
