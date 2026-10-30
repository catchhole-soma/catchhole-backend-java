-- GH312 S4: first time a validation report became INVALIDATED (source/baseline change, source purge or recheck retirement), for the history API.
-- Not backfilled: reports invalidated before this migration keep NULL because the original time was never recorded.
ALTER TABLE validation_reports ADD COLUMN invalidated_at TIMESTAMP;
ALTER TABLE validation_reports ADD CONSTRAINT ck_validation_report_invalidated_at CHECK (invalidated_at IS NULL OR status = 'INVALIDATED');
COMMENT ON COLUMN validation_reports.invalidated_at IS 'Time of the first transition to INVALIDATED. NULL while valid and for reports invalidated before V100.';
