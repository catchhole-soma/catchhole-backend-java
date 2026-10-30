-- GH312 S1: author judgments for current validation findings. No row means PENDING.
-- Saving a judgment never creates extraction jobs, candidates or confirmations.
CREATE TABLE validation_judgments (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    finding_id UUID NOT NULL UNIQUE REFERENCES validation_findings(id),
    branch VARCHAR(20) NOT NULL CHECK (branch IN ('SETTING','TYPO')),
    check_id UUID REFERENCES validation_checks(id),
    check_revision BIGINT,
    judgment VARCHAR(20) NOT NULL CHECK (judgment IN ('PENDING','NEEDS_FIX','NOT_CONFLICT','KEEP_ORIGINAL')),
    version BIGINT NOT NULL CHECK (version > 0),
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_validation_judgment_owner CHECK ((branch='SETTING' AND check_id IS NOT NULL AND check_revision>0) OR (branch='TYPO' AND check_id IS NULL AND check_revision IS NULL)),
    CONSTRAINT ck_validation_judgment_branch_value CHECK ((branch='SETTING' AND judgment IN ('PENDING','NEEDS_FIX','NOT_CONFLICT')) OR (branch='TYPO' AND judgment IN ('PENDING','NEEDS_FIX','KEEP_ORIGINAL'))),
    CONSTRAINT ck_validation_judgment_decided CHECK ((judgment='PENDING') = (decided_at IS NULL))
);
CREATE INDEX idx_validation_judgments_report ON validation_judgments(report_id);
CREATE INDEX idx_validation_judgments_check ON validation_judgments(check_id,check_revision);
CREATE TABLE validation_judgment_history (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES validation_reports(id),
    finding_id UUID NOT NULL REFERENCES validation_findings(id),
    check_id UUID REFERENCES validation_checks(id),
    check_revision BIGINT,
    from_judgment VARCHAR(20) CHECK (from_judgment IN ('PENDING','NEEDS_FIX','NOT_CONFLICT','KEEP_ORIGINAL')),
    to_judgment VARCHAR(20) NOT NULL CHECK (to_judgment IN ('PENDING','NEEDS_FIX','NOT_CONFLICT','KEEP_ORIGINAL')),
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('AUTHOR','REVISION_SUPERSEDED')),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_validation_judgment_history_report ON validation_judgment_history(report_id);
CREATE INDEX idx_validation_judgment_history_finding ON validation_judgment_history(finding_id);
COMMENT ON TABLE validation_judgments IS 'Current author judgment per finding row. Created on the first author decision; a missing row is PENDING. Stale-revision rows stay as history.';
COMMENT ON COLUMN validation_judgments.version IS 'Explicit judgment CAS. Starts at 1 and advances only on an effective value change.';
COMMENT ON TABLE validation_judgment_history IS 'Append-only judgment transitions. AUTHOR is an author save; REVISION_SUPERSEDED records that the finding check revision was replaced and the new revision is PENDING. Deleted with the report payload on invalidation and work purge.';
