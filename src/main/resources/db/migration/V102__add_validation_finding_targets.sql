-- #314 B2: setting finding target (fixed baseline subject and setting) and impact grade, copied once when the finding is saved,
-- so the review list can show and filter them in SQL without reading payload/check/baseline JSON per row.
-- The write path (ValidationResultServiceImpl.publishValidationUnit) fills them from the question state and the report's frozen baseline entries;
-- current character/world values are never read. TYPO findings keep every column NULL.
ALTER TABLE validation_findings
    ADD COLUMN impact VARCHAR(20),
    ADD COLUMN subject_type VARCHAR(20),
    ADD COLUMN subject_id UUID,
    ADD COLUMN subject_name TEXT,
    ADD COLUMN setting_key TEXT,
    ADD COLUMN setting_name TEXT;
ALTER TABLE validation_findings ADD CONSTRAINT ck_validation_finding_impact
    CHECK (impact IS NULL OR (branch = 'SETTING' AND impact IN ('SEVERE', 'CAUTION', 'REFERENCE')));
ALTER TABLE validation_findings ADD CONSTRAINT ck_validation_finding_subject_type
    CHECK (subject_type IS NULL OR (branch = 'SETTING' AND subject_type IN ('CHARACTER', 'WORLD_SETTING')));
ALTER TABLE validation_findings ADD CONSTRAINT ck_validation_finding_subject_identity
    CHECK (subject_type IS NOT NULL OR (subject_id IS NULL AND subject_name IS NULL AND setting_key IS NULL AND setting_name IS NULL));
ALTER TABLE validation_findings ADD CONSTRAINT ck_validation_finding_setting
    CHECK ((setting_key IS NULL) = (setting_name IS NULL));
COMMENT ON COLUMN validation_findings.impact IS 'SETTING payload impact (SEVERE/CAUTION/REFERENCE). NULL for TYPO.';
COMMENT ON COLUMN validation_findings.subject_type IS 'CHARACTER or WORLD_SETTING of the fixed baseline entry the question targets. NULL for TYPO or when no baseline entry is targeted.';
COMMENT ON COLUMN validation_findings.subject_id IS 'Actual character/world setting id of that baseline entry. NULL when the entry is not linked to an actual target.';
COMMENT ON COLUMN validation_findings.subject_name IS 'Subject name as frozen in the baseline entry.';
COMMENT ON COLUMN validation_findings.setting_key IS 'Character slot key "FACTTYPE:factKey" or world path key ["scope","name"]/[null,"name"]. Set only when property_or_rule names exactly one frozen slot/path. NULL for rows saved before V102.';
COMMENT ON COLUMN validation_findings.setting_name IS 'Display name of setting_key. NULL whenever setting_key is NULL.';

-- B2 backfill (idempotent; only rows whose new columns are still NULL).
-- impact: copied from the stored payload.
UPDATE validation_findings
SET impact = payload ->> 'impact'
WHERE branch = 'SETTING' AND impact IS NULL AND payload ->> 'impact' IN ('SEVERE', 'CAUTION', 'REFERENCE');

-- subject: same rule as ValidationFindingTargets.describe. The question state of the finding's revision (check history for an older
-- revision, else the current check) gives subject_refs; S-refs follow ValidationDataMapper.toSubjectRefs (first occurrence order of the
-- baseline entry identity). The first listed S-ref (else the BASELINE lookup entries) picks the entry, preferring characters/worldSettings,
-- then baseline order. setting_key/setting_name are not backfilled: the display-name rule needs the Java schema resolver, so pre-V102 rows keep NULL.
WITH setting_findings AS (
    SELECT f.id, f.report_id, COALESCE(h.state, c.state) AS state
    FROM validation_findings f
    LEFT JOIN validation_check_history h ON h.check_id = f.check_id AND h.revision = f.check_revision
    LEFT JOIN validation_checks c ON c.id = f.check_id AND c.revision = f.check_revision
    WHERE f.branch = 'SETTING' AND f.subject_type IS NULL
),
entries AS (
    SELECT r.id AS report_id, e.idx, e.entry, e.entry ->> 'domain' AS domain,
           COALESCE(e.entry ->> 'subjectRef', 'world:' || COALESCE(e.entry -> 'state' ->> 'targetWorldSettingId', 'B' || e.idx)) AS identity
    FROM validation_reports r
    CROSS JOIN LATERAL jsonb_array_elements(r.baseline_entries) WITH ORDINALITY AS e(entry, idx)
    WHERE jsonb_typeof(r.baseline_entries) = 'array' AND r.id IN (SELECT report_id FROM setting_findings)
),
identities AS (
    SELECT report_id, identity, MIN(idx) AS first_idx FROM entries GROUP BY report_id, identity
),
refs AS (
    SELECT e.report_id, e.idx, e.entry, e.domain, e.identity,
           'S' || DENSE_RANK() OVER (PARTITION BY e.report_id ORDER BY i.first_idx) AS subject_ref
    FROM entries e JOIN identities i ON i.report_id = e.report_id AND i.identity = e.identity
),
picked AS (
    SELECT sf.id AS finding_id,
           COALESCE(
               (SELECT r.idx
                FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(sf.state -> 'draft' -> 'subject_refs') = 'array'
                                                    THEN sf.state -> 'draft' -> 'subject_refs' ELSE '[]'::jsonb END) WITH ORDINALITY AS s(subject_ref, ord)
                JOIN refs r ON r.report_id = sf.report_id AND r.subject_ref = s.subject_ref
                ORDER BY s.ord, (r.domain NOT IN ('characters', 'worldSettings')), r.idx
                LIMIT 1),
               (SELECT r.idx
                FROM refs r
                WHERE r.report_id = sf.report_id AND ('B' || r.idx) IN (
                    SELECT ref
                    FROM jsonb_array_elements(CASE WHEN jsonb_typeof(sf.state -> 'lookup_obligations') = 'array'
                                                   THEN sf.state -> 'lookup_obligations' ELSE '[]'::jsonb END) AS o(intent)
                    CROSS JOIN LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(o.intent -> 'evidence_refs') = 'array'
                                                                      THEN o.intent -> 'evidence_refs' ELSE '[]'::jsonb END) AS x(ref)
                    WHERE o.intent ->> 'operation' = 'BASELINE')
                ORDER BY (r.domain NOT IN ('characters', 'worldSettings')), r.idx
                LIMIT 1)) AS idx,
           sf.report_id
    FROM setting_findings sf
    WHERE sf.state IS NOT NULL
),
targets AS (
    SELECT p.finding_id,
           CASE WHEN r.domain = 'characters' THEN 'CHARACTER' ELSE 'WORLD_SETTING' END AS subject_type,
           CASE WHEN r.domain = 'characters' AND r.identity ~ '^character:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                    THEN CAST(substring(r.identity FROM 11) AS UUID)
                WHEN r.domain <> 'characters' AND r.identity ~ '^world:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                    THEN CAST(substring(r.identity FROM 7) AS UUID)
           END AS subject_id,
           CASE WHEN jsonb_typeof(r.entry -> 'state' -> 'name') = 'string' AND btrim(r.entry -> 'state' ->> 'name') <> '' THEN r.entry -> 'state' ->> 'name'
                WHEN jsonb_typeof(r.entry -> 'state' -> 'subjectName') = 'string' AND btrim(r.entry -> 'state' ->> 'subjectName') <> '' THEN r.entry -> 'state' ->> 'subjectName'
           END AS subject_name
    FROM picked p JOIN refs r ON r.report_id = p.report_id AND r.idx = p.idx
)
UPDATE validation_findings f
SET subject_type = t.subject_type, subject_id = t.subject_id, subject_name = t.subject_name
FROM targets t
WHERE f.id = t.finding_id AND f.subject_type IS NULL;
