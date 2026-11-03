-- Continuation is an explicit closure, not a human rejection. Original evidence,
-- comparison decisions and failure diagnostics remain untouched.
ALTER TABLE setting_candidates ADD COLUMN closed_at TIMESTAMP;
ALTER TABLE setting_candidates ADD COLUMN closure_request_id UUID;
ALTER TABLE world_setting_candidates ADD COLUMN closed_at TIMESTAMP;
ALTER TABLE world_setting_candidates ADD COLUMN closure_request_id UUID;

ALTER TABLE world_setting_candidates DROP CONSTRAINT ck_world_setting_candidates_review_status;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_review_status
    CHECK (review_status IN ('PENDING_REVIEW', 'CONFIRMED', 'DISMISSED', 'CLOSED'));

ALTER TABLE setting_candidates ADD CONSTRAINT ck_setting_candidates_closure
    CHECK ((review_status = 'CLOSED' AND closed_at IS NOT NULL AND closure_request_id IS NOT NULL)
        OR (review_status <> 'CLOSED' AND closed_at IS NULL AND closure_request_id IS NULL));
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_setting_candidates_closure
    CHECK ((review_status = 'CLOSED' AND closed_at IS NOT NULL AND closure_request_id IS NOT NULL)
        OR (review_status <> 'CLOSED' AND closed_at IS NULL AND closure_request_id IS NULL));
