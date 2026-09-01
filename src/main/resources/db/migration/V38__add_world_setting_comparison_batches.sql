CREATE TABLE world_setting_comparison_batches (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    source_episode_id UUID NOT NULL,
    analysis_job_id UUID NOT NULL,
    category VARCHAR(40) NOT NULL,
    raw_scope_name VARCHAR(100),
    subject_resolution_type VARCHAR(20) NOT NULL,
    canonical_subject_key VARCHAR(150) NOT NULL,
    canonical_subject_name VARCHAR(100) NOT NULL,
    resolved_target_world_setting_ids JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    candidate_count INTEGER NOT NULL,
    context_snapshot_json JSONB,
    raw_completion_json JSONB,
    completion_hash VARCHAR(64),
    failure_code VARCHAR(60),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_world_setting_comparison_batches_work
        FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT fk_world_setting_comparison_batches_episode
        FOREIGN KEY (source_episode_id) REFERENCES episodes (id),
    CONSTRAINT fk_world_setting_comparison_batches_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE CASCADE,
    CONSTRAINT ck_world_setting_comparison_batches_category
        CHECK (category IN (
            'RACE',
            'FACTION',
            'LOCATION',
            'MONSTER',
            'POWER_SYSTEM',
            'WORLD_RULE_HISTORY',
            'IMPORTANT_ITEM'
        )),
    CONSTRAINT ck_world_setting_comparison_batches_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'REVIEW_REQUIRED')),
    CONSTRAINT ck_world_setting_comparison_batches_subject_resolution_type
        CHECK (subject_resolution_type IN ('NEW', 'EXISTING', 'AMBIGUOUS')),
    CONSTRAINT ck_world_setting_comparison_batches_resolved_target_ids
        CHECK (
            jsonb_typeof(resolved_target_world_setting_ids) = 'array'
            AND (
                (subject_resolution_type = 'NEW'
                    AND jsonb_array_length(resolved_target_world_setting_ids) = 0)
                OR (subject_resolution_type = 'EXISTING'
                    AND jsonb_array_length(resolved_target_world_setting_ids) = 1)
                OR (subject_resolution_type = 'AMBIGUOUS'
                    AND jsonb_array_length(resolved_target_world_setting_ids) > 1)
            )
        ),
    CONSTRAINT ck_world_setting_comparison_batches_candidate_count
        CHECK (candidate_count > 0),
    CONSTRAINT ck_world_setting_comparison_batches_completion_hash
        CHECK (completion_hash IS NULL OR completion_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_world_setting_comparison_batches_job_status
    ON world_setting_comparison_batches (analysis_job_id, status, created_at, id);

CREATE TABLE world_setting_comparison_decisions (
    id UUID PRIMARY KEY,
    comparison_batch_id UUID NOT NULL,
    decision_ref VARCHAR(20) NOT NULL,
    canonical_subject_name VARCHAR(100) NOT NULL,
    target_world_setting_id UUID,
    matched_scope_name VARCHAR(100),
    matched_property_name VARCHAR(100),
    consolidation_status VARCHAR(20) NOT NULL,
    suggested_operation VARCHAR(30) NOT NULL,
    comparison_review_reason VARCHAR(40),
    proposed_scope_name VARCHAR(100),
    proposed_setting_name VARCHAR(100) NOT NULL,
    before_value TEXT,
    proposed_value TEXT NOT NULL,
    comparison_reason TEXT NOT NULL,
    base_world_setting_version BIGINT,
    raw_comparison_json JSONB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_world_setting_comparison_decisions_batch
        FOREIGN KEY (comparison_batch_id)
        REFERENCES world_setting_comparison_batches (id) ON DELETE CASCADE,
    CONSTRAINT fk_world_setting_comparison_decisions_target
        FOREIGN KEY (target_world_setting_id)
        REFERENCES world_settings (id) ON DELETE SET NULL,
    CONSTRAINT uk_world_setting_comparison_decisions_ref
        UNIQUE (comparison_batch_id, decision_ref),
    CONSTRAINT ck_world_setting_comparison_decisions_consolidation
        CHECK (consolidation_status IN ('SINGLE', 'MERGED', 'CONFLICT')),
    CONSTRAINT ck_world_setting_comparison_decisions_operation
        CHECK (suggested_operation IN (
            'ADD', 'UPDATE', 'MERGE', 'EXCLUDE', 'REVIEW_REQUIRED'
        )),
    CONSTRAINT ck_world_setting_comparison_decisions_review_reason
        CHECK (
            comparison_review_reason IS NULL
            OR comparison_review_reason IN ('SCOPE_UNRESOLVED', 'BATCH_LIMIT_EXCEEDED')
        ),
    CONSTRAINT ck_world_setting_comparison_decisions_base_version
        CHECK (base_world_setting_version IS NULL OR base_world_setting_version >= 0)
);

CREATE INDEX idx_world_setting_comparison_decisions_batch
    ON world_setting_comparison_decisions (comparison_batch_id, decision_ref, id);
CREATE INDEX idx_world_setting_comparison_decisions_target
    ON world_setting_comparison_decisions (target_world_setting_id);

ALTER TABLE world_setting_candidates
    ADD COLUMN comparison_batch_id UUID,
    ADD COLUMN comparison_decision_id UUID,
    ADD COLUMN comparison_candidate_ref VARCHAR(20),
    ADD COLUMN subject_resolution_type VARCHAR(20),
    ADD COLUMN canonical_subject_key VARCHAR(150),
    ADD COLUMN canonical_subject_name VARCHAR(100),
    ADD COLUMN resolved_target_world_setting_ids JSONB;

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT fk_world_setting_candidates_comparison_batch
        FOREIGN KEY (comparison_batch_id)
        REFERENCES world_setting_comparison_batches (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_world_setting_candidates_comparison_decision
        FOREIGN KEY (comparison_decision_id)
        REFERENCES world_setting_comparison_decisions (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uk_world_setting_candidates_comparison_batch_ref
    ON world_setting_candidates (comparison_batch_id, comparison_candidate_ref)
    WHERE comparison_batch_id IS NOT NULL;
CREATE INDEX idx_world_setting_candidates_comparison_decision
    ON world_setting_candidates (comparison_decision_id);
CREATE INDEX idx_world_setting_candidates_subject_resolution
    ON world_setting_candidates (
        analysis_job_id,
        comparison_status,
        subject_resolution_type,
        canonical_subject_key,
        created_at,
        id
    );

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_type
        CHECK (
            subject_resolution_type IS NULL
            OR subject_resolution_type IN ('NEW', 'EXISTING', 'AMBIGUOUS')
        ),
    ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_fields
        CHECK (
            (subject_resolution_type IS NULL
                AND canonical_subject_key IS NULL
                AND canonical_subject_name IS NULL
                AND resolved_target_world_setting_ids IS NULL)
            OR (subject_resolution_type IS NOT NULL
                AND canonical_subject_key IS NOT NULL
                AND canonical_subject_name IS NOT NULL
                AND resolved_target_world_setting_ids IS NOT NULL
                AND jsonb_typeof(resolved_target_world_setting_ids) = 'array')
        ),
    ADD CONSTRAINT ck_world_setting_candidates_subject_resolution_cardinality
        CHECK (
            subject_resolution_type IS NULL
            OR (subject_resolution_type = 'NEW'
                AND jsonb_array_length(resolved_target_world_setting_ids) = 0)
            OR (subject_resolution_type = 'EXISTING'
                AND jsonb_array_length(resolved_target_world_setting_ids) = 1)
            OR (subject_resolution_type = 'AMBIGUOUS'
                AND jsonb_array_length(resolved_target_world_setting_ids) > 1)
        );

CREATE TABLE world_setting_comparison_decision_sources (
    id UUID PRIMARY KEY,
    comparison_batch_id UUID NOT NULL,
    comparison_decision_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    candidate_ref VARCHAR(20) NOT NULL,
    source_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_world_setting_comparison_sources_batch
        FOREIGN KEY (comparison_batch_id)
        REFERENCES world_setting_comparison_batches (id) ON DELETE CASCADE,
    CONSTRAINT fk_world_setting_comparison_sources_decision
        FOREIGN KEY (comparison_decision_id)
        REFERENCES world_setting_comparison_decisions (id) ON DELETE CASCADE,
    CONSTRAINT fk_world_setting_comparison_sources_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES world_setting_candidates (id) ON DELETE CASCADE,
    CONSTRAINT uk_world_setting_comparison_sources_candidate
        UNIQUE (comparison_batch_id, candidate_id),
    CONSTRAINT uk_world_setting_comparison_sources_ref
        UNIQUE (comparison_batch_id, candidate_ref),
    CONSTRAINT uk_world_setting_comparison_sources_order
        UNIQUE (comparison_decision_id, source_order),
    CONSTRAINT ck_world_setting_comparison_sources_order
        CHECK (source_order >= 0)
);

CREATE INDEX idx_world_setting_comparison_sources_decision
    ON world_setting_comparison_decision_sources (comparison_decision_id, source_order, id);

ALTER TABLE world_setting_candidates
    DROP CONSTRAINT ck_world_setting_candidates_comparison_review_reason;

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_comparison_review_reason
        CHECK (
            (suggested_operation IS NOT NULL
                AND suggested_operation = 'REVIEW_REQUIRED'
                AND comparison_review_reason IS NOT NULL
                AND comparison_review_reason IN ('SCOPE_UNRESOLVED', 'BATCH_LIMIT_EXCEEDED'))
            OR ((suggested_operation IS NULL OR suggested_operation <> 'REVIEW_REQUIRED')
                AND comparison_review_reason IS NULL)
        );

ALTER TABLE world_setting_candidates
    DROP CONSTRAINT ck_world_setting_candidates_comparison_source_reason_code;

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_comparison_source_reason_code
        CHECK (
            comparison_source_reason_code IS NULL
            OR comparison_source_reason_code IN (
                'CONTEXT_TARGET_DUPLICATED',
                'CONTEXT_TARGET_NOT_FOUND',
                'CONTEXT_VERSION_DUPLICATED',
                'EXACT_TARGET_NOT_IN_CONTEXT',
                'SELECTED_TARGET_NOT_IN_CONTEXT',
                'REVIEW_REASON_FORBIDDEN',
                'MATCHED_TARGET_REQUIRED',
                'PROPOSED_PATH_MISMATCH',
                'ADD_MATCHED_PATH_FORBIDDEN',
                'SCOPE_REVIEW_REQUIRED',
                'PROPOSED_PATH_CONFLICT',
                'EXCLUDE_MATCHED_PATH_INVALID',
                'EXCLUDE_MATCHED_SCOPE_WITHOUT_PROPERTY',
                'SCOPE_REVIEW_REASON_INVALID',
                'SCOPE_REVIEW_TARGET_REQUIRED',
                'SCOPE_REVIEW_CANDIDATE_ALREADY_SCOPED',
                'SCOPE_REVIEW_MATCHED_PATH_REQUIRED',
                'SCOPE_REVIEW_ROOT_PATH_EXISTS',
                'SCOPE_REVIEW_MATCHED_PATH_INVALID',
                'BATCH_CONTEXT_NOT_INITIALIZED',
                'BATCH_DECISION_REF_DUPLICATED',
                'BATCH_SOURCE_REF_DUPLICATED',
                'BATCH_SOURCE_REF_UNKNOWN',
                'BATCH_SOURCE_COVERAGE_INVALID',
                'BATCH_RESOLVED_TARGET_COVERAGE_INVALID',
                'BATCH_CANONICAL_SUBJECT_INVALID',
                'BATCH_CONSOLIDATION_STATUS_INVALID',
                'BATCH_SOURCE_SCOPE_INVALID'
            )
        );
