CREATE TABLE character_fact_comparison_batches (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    source_episode_id UUID,
    analysis_job_id UUID NOT NULL,
    matched_character_id UUID NOT NULL,
    canonical_fact_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    candidate_count INTEGER NOT NULL,
    base_snapshot_version BIGINT NOT NULL,
    context_hash VARCHAR(64),
    completion_hash VARCHAR(64),
    raw_completion_json JSONB,
    failure_code VARCHAR(60),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_character_fact_comparison_batches_work
        FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    CONSTRAINT fk_character_fact_comparison_batches_episode
        FOREIGN KEY (source_episode_id) REFERENCES episodes (id),
    CONSTRAINT fk_character_fact_comparison_batches_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_character_fact_comparison_batches_character
        FOREIGN KEY (matched_character_id) REFERENCES characters (id) ON DELETE CASCADE,
    CONSTRAINT ck_character_fact_comparison_batches_fact_type
        CHECK (canonical_fact_type IN (
            'PROFILE', 'AGE', 'LEVEL', 'STAT', 'SKILL', 'ITEM', 'STATUS', 'TIME'
        )),
    CONSTRAINT ck_character_fact_comparison_batches_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_character_fact_comparison_batches_candidate_count
        CHECK (candidate_count > 0),
    CONSTRAINT ck_character_fact_comparison_batches_snapshot_version
        CHECK (base_snapshot_version >= 0),
    CONSTRAINT ck_character_fact_comparison_batches_context_hash
        CHECK (context_hash IS NULL OR context_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_character_fact_comparison_batches_completion_hash
        CHECK (completion_hash IS NULL OR completion_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_character_fact_comparison_batches_job_status
    ON character_fact_comparison_batches (analysis_job_id, status, created_at, id);

ALTER TABLE setting_candidates
    ADD COLUMN character_comparison_batch_id UUID,
    ADD COLUMN character_comparison_candidate_ref VARCHAR(20),
    ADD COLUMN resolved_canonical_fact_key VARCHAR(150),
    ADD COLUMN comparison_dependency_candidate_ids JSONB;

ALTER TABLE setting_candidates
    ADD CONSTRAINT fk_setting_candidates_character_comparison_batch
        FOREIGN KEY (character_comparison_batch_id)
        REFERENCES character_fact_comparison_batches (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_setting_candidates_character_comparison_ref
        CHECK (
            (character_comparison_batch_id IS NULL
                AND character_comparison_candidate_ref IS NULL)
            OR (character_comparison_candidate_ref IS NOT NULL
                AND character_comparison_candidate_ref ~ '^C[1-9][0-9]*$')
        ),
    ADD CONSTRAINT ck_setting_candidates_character_comparison_dependencies
        CHECK (
            comparison_dependency_candidate_ids IS NULL
            OR jsonb_typeof(comparison_dependency_candidate_ids) = 'array'
        );

CREATE UNIQUE INDEX uk_setting_candidates_character_comparison_batch_ref
    ON setting_candidates (character_comparison_batch_id, character_comparison_candidate_ref)
    WHERE character_comparison_batch_id IS NOT NULL;

CREATE INDEX idx_setting_candidates_character_comparison_batch
    ON setting_candidates (character_comparison_batch_id, character_comparison_candidate_ref, id);
