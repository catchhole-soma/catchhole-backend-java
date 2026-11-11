-- 개인을 삭제한 뒤에는 날짜/월 합계만 남긴다. 사업 데이터에 대한 FK는
-- Member → Work → Job → 집계 상태 잠금과 역순의 FK 잠금을 만들지 않도록 두지 않는다.
CREATE TABLE product_usage_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    started_at TIMESTAMP,
    collected_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
INSERT INTO product_usage_state(id, created_at, updated_at)
VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE product_usage_daily (
    period_start DATE PRIMARY KEY,
    members_1d BIGINT NOT NULL DEFAULT 0 CHECK (members_1d >= 0),
    members_7d BIGINT NOT NULL DEFAULT 0 CHECK (members_7d >= 0),
    members_30d BIGINT NOT NULL DEFAULT 0 CHECK (members_30d >= 0),
    completed_requests BIGINT NOT NULL DEFAULT 0 CHECK (completed_requests >= 0),
    repeat_requests BIGINT NOT NULL DEFAULT 0 CHECK (repeat_requests >= 0),
    unknown_requests BIGINT NOT NULL DEFAULT 0 CHECK (unknown_requests >= 0),
    works_created BIGINT NOT NULL DEFAULT 0 CHECK (works_created >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE product_usage_monthly (
    period_start DATE PRIMARY KEY,
    analysis_members BIGINT NOT NULL DEFAULT 0 CHECK (analysis_members >= 0),
    completed_requests BIGINT NOT NULL DEFAULT 0 CHECK (completed_requests >= 0),
    repeat_requests BIGINT NOT NULL DEFAULT 0 CHECK (repeat_requests >= 0),
    unknown_requests BIGINT NOT NULL DEFAULT 0 CHECK (unknown_requests >= 0),
    works_created BIGINT NOT NULL DEFAULT 0 CHECK (works_created >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE product_usage_members (
    member_id BIGINT PRIMARY KEY,
    prior_history_unknown BOOLEAN NOT NULL,
    first_completed_at TIMESTAMP,
    first_request_id UUID,
    first_kind VARCHAR(20),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE product_usage_member_days (
    id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    activity_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (member_id, activity_date)
);
CREATE TABLE product_usage_analysis_receipts (
    request_id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    member_id BIGINT NOT NULL,
    requested_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_product_usage_receipts_work ON product_usage_analysis_receipts(work_id);
CREATE INDEX idx_product_usage_receipts_member ON product_usage_analysis_receipts(member_id);
