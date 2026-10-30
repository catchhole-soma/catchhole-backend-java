-- GH312 S2: why the automatic review completion inside /complete was rolled back, kept for the author UI (#314).
-- Set only by the fallback /complete transaction; cleared when a review-completion receipt is created.
ALTER TABLE validation_reports ADD COLUMN release_hold_code VARCHAR(60), ADD COLUMN release_hold_at TIMESTAMP;
ALTER TABLE validation_reports ADD CONSTRAINT ck_validation_report_release_hold CHECK ((release_hold_code IS NULL) = (release_hold_at IS NULL));
COMMENT ON COLUMN validation_reports.release_hold_code IS 'AppException code that rolled back the automatic review completion and its extraction job. Null when not held or after a receipt exists.';
COMMENT ON COLUMN validation_reports.release_hold_at IS 'Time the automatic review completion was held. Null together with release_hold_code.';
