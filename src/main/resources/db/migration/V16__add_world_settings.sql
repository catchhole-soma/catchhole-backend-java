CREATE TABLE world_settings (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    category VARCHAR(40) NOT NULL,
    subject_name VARCHAR(100) NOT NULL,
    normalized_subject_name VARCHAR(100) NOT NULL,
    properties_json JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_world_settings_work_category_subject
        UNIQUE (work_id, category, normalized_subject_name),
    CONSTRAINT fk_world_settings_work
        FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT ck_world_settings_category
        CHECK (category IN (
            'RACE',
            'FACTION',
            'LOCATION',
            'MONSTER',
            'POWER_SYSTEM',
            'WORLD_RULE_HISTORY',
            'IMPORTANT_ITEM'
        )),
    CONSTRAINT ck_world_settings_properties_object
        CHECK (jsonb_typeof(properties_json) = 'object')
);

CREATE INDEX idx_world_settings_work_category_subject
    ON world_settings (work_id, category, normalized_subject_name, id);
CREATE INDEX idx_world_settings_work_updated
    ON world_settings (work_id, updated_at DESC, id);

CREATE TABLE world_setting_candidates (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    source_episode_id UUID NOT NULL,
    analysis_job_id UUID NOT NULL,
    category VARCHAR(40) NOT NULL,
    subject_name VARCHAR(100) NOT NULL,
    setting_name VARCHAR(100) NOT NULL,
    extracted_value TEXT NOT NULL,
    evidence_spans JSONB NOT NULL,
    extraction_confidence NUMERIC(5, 4),
    raw_extraction_json JSONB,
    target_world_setting_id UUID,
    suggested_operation VARCHAR(30),
    proposed_setting_name VARCHAR(100),
    before_value TEXT,
    proposed_value TEXT,
    comparison_reason TEXT,
    base_world_setting_version BIGINT,
    raw_comparison_json JSONB,
    compared_at TIMESTAMP,
    comparison_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    comparison_error_message TEXT,
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    final_operation VARCHAR(30),
    final_category VARCHAR(40),
    final_subject_name VARCHAR(100),
    final_setting_name VARCHAR(100),
    final_value TEXT,
    review_note TEXT,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMP,
    applied_world_setting_version BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_world_setting_candidates_work
        FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT fk_world_setting_candidates_source_episode
        FOREIGN KEY (source_episode_id) REFERENCES episodes (id),
    CONSTRAINT fk_world_setting_candidates_analysis_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id),
    CONSTRAINT fk_world_setting_candidates_target
        FOREIGN KEY (target_world_setting_id) REFERENCES world_settings (id),
    CONSTRAINT fk_world_setting_candidates_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES members (id),
    CONSTRAINT ck_world_setting_candidates_category
        CHECK (category IN (
            'RACE',
            'FACTION',
            'LOCATION',
            'MONSTER',
            'POWER_SYSTEM',
            'WORLD_RULE_HISTORY',
            'IMPORTANT_ITEM'
        )),
    CONSTRAINT ck_world_setting_candidates_final_category
        CHECK (
            final_category IS NULL
            OR final_category IN (
                'RACE',
                'FACTION',
                'LOCATION',
                'MONSTER',
                'POWER_SYSTEM',
                'WORLD_RULE_HISTORY',
                'IMPORTANT_ITEM'
            )
        ),
    CONSTRAINT ck_world_setting_candidates_suggested_operation
        CHECK (
            suggested_operation IS NULL
            OR suggested_operation IN ('ADD', 'UPDATE', 'MERGE', 'EXCLUDE')
        ),
    CONSTRAINT ck_world_setting_candidates_final_operation
        CHECK (
            final_operation IS NULL
            OR final_operation IN ('ADD', 'UPDATE', 'MERGE', 'EXCLUDE')
        ),
    CONSTRAINT ck_world_setting_candidates_comparison_status
        CHECK (comparison_status IN (
            'PENDING',
            'PROCESSING',
            'COMPLETED',
            'FAILED',
            'RECOMPARISON_REQUIRED'
        )),
    CONSTRAINT ck_world_setting_candidates_review_status
        CHECK (review_status IN ('PENDING_REVIEW', 'CONFIRMED', 'DISMISSED')),
    CONSTRAINT ck_world_setting_candidates_evidence_array
        CHECK (jsonb_typeof(evidence_spans) = 'array'),
    CONSTRAINT ck_world_setting_candidates_confidence
        CHECK (
            extraction_confidence IS NULL
            OR (extraction_confidence >= 0 AND extraction_confidence <= 1)
        ),
    CONSTRAINT ck_world_setting_candidates_version_values
        CHECK (
            (base_world_setting_version IS NULL OR base_world_setting_version >= 0)
            AND (applied_world_setting_version IS NULL OR applied_world_setting_version >= 0)
        )
);

CREATE INDEX idx_world_setting_candidates_job_review
    ON world_setting_candidates (analysis_job_id, review_status);
CREATE INDEX idx_world_setting_candidates_work_review_category
    ON world_setting_candidates (work_id, review_status, category, created_at, id);
CREATE INDEX idx_world_setting_candidates_source_episode
    ON world_setting_candidates (source_episode_id);
CREATE INDEX idx_world_setting_candidates_target
    ON world_setting_candidates (target_world_setting_id);
