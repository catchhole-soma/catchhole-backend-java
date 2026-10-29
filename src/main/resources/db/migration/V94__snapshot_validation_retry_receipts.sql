-- Retry replay returns the original admission receipt, without reapplying a changed runtime budget.
ALTER TABLE validation_request_keys ADD COLUMN response_snapshot JSONB;
COMMENT ON COLUMN validation_request_keys.response_snapshot IS 'Hash/ID/status-only immutable RETRY receipt. No manuscript, result bundle or checkpoint is copied here.';
