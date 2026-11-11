CREATE TABLE product_usage_work_receipts (
    work_id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_product_usage_work_receipts_pending ON product_usage_work_receipts(occurred_at)
    WHERE processed = FALSE;
CREATE INDEX idx_product_usage_work_receipts_member ON product_usage_work_receipts(member_id);
