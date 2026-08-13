ALTER TABLE characters
    ADD COLUMN snapshot_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE character_facts
    ADD CONSTRAINT uk_character_facts_character_id_id
        UNIQUE (character_id, id);

CREATE TABLE character_snapshot_sources (
    id UUID PRIMARY KEY,
    character_id UUID NOT NULL,
    fact_type VARCHAR(30) NOT NULL,
    fact_key VARCHAR(150) NOT NULL,
    source_fact_id UUID NOT NULL,
    source_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_character_snapshot_sources_character
        FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE,
    CONSTRAINT fk_character_snapshot_sources_character_fact
        FOREIGN KEY (character_id, source_fact_id)
        REFERENCES character_facts (character_id, id) ON DELETE CASCADE,
    CONSTRAINT uk_character_snapshot_sources_slot_source
        UNIQUE (character_id, fact_type, fact_key, source_fact_id),
    CONSTRAINT uk_character_snapshot_sources_slot_order
        UNIQUE (character_id, fact_type, fact_key, source_order),
    CONSTRAINT ck_character_snapshot_sources_order_non_negative
        CHECK (source_order >= 0)
);

CREATE INDEX idx_character_snapshot_sources_source_fact
    ON character_snapshot_sources (source_fact_id);

INSERT INTO character_snapshot_sources (
    id,
    character_id,
    fact_type,
    fact_key,
    source_fact_id,
    source_order,
    created_at,
    updated_at
)
SELECT gen_random_uuid(),
       ranked_fact.character_id,
       ranked_fact.fact_type,
       ranked_fact.fact_key,
       ranked_fact.id,
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (
    SELECT current_fact.*,
           ROW_NUMBER() OVER (
               PARTITION BY current_fact.character_id, current_fact.fact_type, current_fact.fact_key
               ORDER BY current_fact.effective_from_episode_no DESC NULLS LAST,
                        current_fact.created_at DESC,
                        current_fact.id DESC
           ) AS current_rank
    FROM character_facts current_fact
    WHERE current_fact.is_current = TRUE
) ranked_fact
WHERE ranked_fact.current_rank = 1;

ALTER TABLE analysis_jobs
    ADD COLUMN setting_candidate_id UUID;

ALTER TABLE analysis_jobs
    ADD CONSTRAINT fk_analysis_jobs_setting_candidate
        FOREIGN KEY (setting_candidate_id)
        REFERENCES setting_candidates (id)
        ON DELETE SET NULL;

CREATE UNIQUE INDEX uk_analysis_jobs_active_setting_candidate
    ON analysis_jobs (setting_candidate_id)
    WHERE setting_candidate_id IS NOT NULL
      AND status IN ('PENDING', 'RUNNING');

ALTER TABLE setting_candidates
    ADD COLUMN comparison_status VARCHAR(40) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN suggested_operation VARCHAR(30),
    ADD COLUMN temporal_scope VARCHAR(30),
    ADD COLUMN comparison_target_fact_type VARCHAR(30),
    ADD COLUMN comparison_target_fact_key VARCHAR(150),
    ADD COLUMN proposed_value_json JSONB,
    ADD COLUMN removed_snapshot_entries_json JSONB,
    ADD COLUMN comparison_reason TEXT,
    ADD COLUMN comparison_base_snapshot_version BIGINT,
    ADD COLUMN comparison_context_hash VARCHAR(64),
    ADD COLUMN raw_comparison_json JSONB,
    ADD COLUMN compared_at TIMESTAMP,
    ADD COLUMN comparison_error_message TEXT;

UPDATE setting_candidates
SET comparison_status = CASE
    WHEN candidate_kind = 'SETTING'
         AND review_status = 'PENDING_REVIEW'
         AND (matched_character_id IS NULL
              OR match_status NOT IN ('MATCHED', 'AUTO_MATCHED_BY_NAME'))
        THEN 'WAITING_FOR_CHARACTER_MATCH'
    ELSE 'NOT_REQUIRED'
END;

ALTER TABLE setting_candidates
    ADD CONSTRAINT ck_setting_candidates_comparison_status
        CHECK (comparison_status IN (
            'NOT_REQUIRED',
            'WAITING_FOR_CHARACTER_MATCH',
            'PENDING',
            'PROCESSING',
            'COMPLETED',
            'FAILED',
            'RECOMPARISON_REQUIRED'
        )),
    ADD CONSTRAINT ck_setting_candidates_suggested_operation
        CHECK (suggested_operation IS NULL OR suggested_operation IN (
            'ADD', 'UPDATE', 'MERGE', 'HISTORY_ONLY', 'EXCLUDE', 'REVIEW_REQUIRED'
        )),
    ADD CONSTRAINT ck_setting_candidates_temporal_scope
        CHECK (temporal_scope IS NULL OR temporal_scope IN (
            'PRESENT', 'PAST', 'HYPOTHETICAL', 'UNKNOWN'
        )),
    ADD CONSTRAINT ck_setting_candidates_comparison_target_fact_type
        CHECK (comparison_target_fact_type IS NULL OR comparison_target_fact_type IN (
            'PROFILE', 'AGE', 'LEVEL', 'STAT', 'SKILL', 'ITEM', 'STATUS', 'TIME'
        ));

CREATE INDEX idx_setting_candidates_comparison_claim
    ON setting_candidates (analysis_job_id, comparison_status, created_at, id);
CREATE INDEX idx_setting_candidates_matched_character_comparison
    ON setting_candidates (matched_character_id, comparison_status);

DROP INDEX idx_character_facts_character_current;

ALTER TABLE character_facts
    DROP COLUMN is_current;
