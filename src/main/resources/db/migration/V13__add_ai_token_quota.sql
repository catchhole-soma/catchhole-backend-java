CREATE TABLE ai_token_accounts (
    member_id BIGINT PRIMARY KEY,
    granted_tokens BIGINT NOT NULL,
    used_tokens BIGINT NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_token_accounts_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_token_accounts_non_negative
        CHECK (granted_tokens >= 0 AND used_tokens >= 0 AND reserved_tokens >= 0)
);

CREATE TABLE ai_token_grants (
    id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    grant_type VARCHAR(30) NOT NULL,
    note VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_token_grants_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_token_grants_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ai_token_grants_member_created
    ON ai_token_grants (member_id, created_at DESC);

CREATE TABLE ai_token_usages (
    request_id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    work_id UUID NOT NULL,
    analysis_job_id UUID NOT NULL,
    purpose VARCHAR(50) NOT NULL,
    attempt INTEGER NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    outcome VARCHAR(30),
    reserved_tokens BIGINT NOT NULL,
    input_tokens BIGINT,
    cached_input_tokens BIGINT,
    output_tokens BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_token_usages_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_token_usages_work
        FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_token_usages_analysis_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_token_usages_attempt_positive CHECK (attempt > 0),
    CONSTRAINT chk_ai_token_usages_reserved_positive CHECK (reserved_tokens > 0)
);

CREATE INDEX idx_ai_token_usages_job_status
    ON ai_token_usages (analysis_job_id, status);
CREATE INDEX idx_ai_token_usages_member_created
    ON ai_token_usages (member_id, created_at DESC);
