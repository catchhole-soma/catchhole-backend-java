CREATE TABLE setting_book_revisions (
    id UUID PRIMARY KEY,
    setting_book_id UUID NOT NULL REFERENCES upload_files(id),
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    content_s3_key VARCHAR(512) NOT NULL,
    content_s3_version VARCHAR(255),
    content_hash VARCHAR(64) NOT NULL,
    char_count INTEGER NOT NULL CHECK (char_count >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_setting_book_revisions_book_revision UNIQUE (setting_book_id, revision_no)
);
ALTER TABLE upload_files ADD COLUMN current_setting_book_revision_id UUID REFERENCES setting_book_revisions(id);
ALTER TABLE analysis_jobs ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT 'EPISODE' CHECK (source_kind IN ('EPISODE','SETTING_BOOK'));
ALTER TABLE analysis_jobs ADD COLUMN source_setting_book_id UUID REFERENCES upload_files(id);
ALTER TABLE analysis_jobs ADD COLUMN source_setting_book_revision_id UUID REFERENCES setting_book_revisions(id);
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_book_source CHECK (
    (source_kind='EPISODE' AND source_setting_book_id IS NULL AND source_setting_book_revision_id IS NULL)
    OR (source_kind='SETTING_BOOK' AND episode_id IS NULL AND source_episode_no IS NULL
        AND source_setting_book_id IS NOT NULL AND source_setting_book_revision_id IS NOT NULL)
);
-- V44/V49 originally required episode identity for every ordered extraction.
-- Preserve all episode run invariants, and admit only a singleton automatic book run.
ALTER TABLE analysis_jobs DROP CONSTRAINT ck_analysis_jobs_ordered_run;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_ordered_run CHECK (
    (analysis_mode = 'CONFIRMED_ONLY' AND analysis_run_id IS NULL AND run_generation IS NULL
        AND run_sequence IS NULL AND predecessor_job_id IS NULL AND run_base_state IS NULL
        AND input_state_hash IS NULL AND state_journal IS NULL AND journal_status IS NULL)
    OR (analysis_mode = 'ORDERED_PROVISIONAL' AND job_type = 'SETTING_EXTRACTION'
        AND analysis_run_id IS NOT NULL AND run_generation IS NOT NULL AND run_generation >= 1
        AND run_sequence IS NOT NULL AND run_sequence >= 0
        AND source_content_hash IS NOT NULL AND source_content_hash ~ '^[0-9a-f]{64}$'
        AND source_content_s3_key IS NOT NULL AND journal_status IS NOT NULL
        AND ((source_kind = 'EPISODE' AND episode_id IS NOT NULL
                AND ((run_sequence = 0 AND predecessor_job_id IS NULL AND run_base_state IS NOT NULL
                        AND jsonb_typeof(run_base_state) = 'object')
                    OR (run_sequence > 0 AND predecessor_job_id IS NOT NULL AND run_base_state IS NULL)))
            OR (source_kind = 'SETTING_BOOK' AND episode_id IS NULL AND source_episode_no IS NULL
                AND source_setting_book_id IS NOT NULL AND source_setting_book_revision_id IS NOT NULL
                AND review_mode = 'AUTOMATIC' AND run_sequence = 0 AND predecessor_job_id IS NULL
                AND run_base_state IS NOT NULL AND jsonb_typeof(run_base_state) = 'object')))
);
ALTER TABLE analysis_jobs DROP CONSTRAINT ck_analysis_jobs_source_episode_no;
ALTER TABLE analysis_jobs ADD CONSTRAINT ck_analysis_jobs_source_episode_no CHECK (
    (source_kind = 'SETTING_BOOK' AND source_episode_no IS NULL)
    OR (source_kind = 'EPISODE' AND ((analysis_mode = 'CONFIRMED_ONLY'
            AND (source_episode_no IS NULL OR source_episode_no > 0))
        OR (analysis_mode = 'ORDERED_PROVISIONAL' AND source_episode_no IS NOT NULL AND source_episode_no > 0)))
);
CREATE INDEX idx_analysis_jobs_book_source ON analysis_jobs(source_setting_book_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX uk_analysis_jobs_active_book_source ON analysis_jobs(source_setting_book_id)
    WHERE source_kind='SETTING_BOOK' AND job_type='SETTING_EXTRACTION' AND status IN ('PENDING','RUNNING');
CREATE TABLE setting_book_chunks (
    id UUID PRIMARY KEY,
    analysis_job_id UUID NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
    setting_book_revision_id UUID NOT NULL REFERENCES setting_book_revisions(id),
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    chunk_text TEXT NOT NULL,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset >= start_offset),
    paragraph_start_index INTEGER NOT NULL,
    paragraph_end_index INTEGER NOT NULL,
    metadata_json JSONB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_setting_book_chunks_job_index UNIQUE (analysis_job_id, chunk_index)
);
CREATE INDEX idx_setting_book_chunks_revision ON setting_book_chunks(setting_book_revision_id);
ALTER TABLE setting_candidates ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT 'EPISODE' CHECK (source_kind IN ('EPISODE','SETTING_BOOK'));
ALTER TABLE setting_candidates ADD COLUMN source_setting_book_id UUID REFERENCES upload_files(id);
ALTER TABLE setting_candidates ADD COLUMN source_setting_book_revision_id UUID REFERENCES setting_book_revisions(id);
ALTER TABLE setting_candidates ADD CONSTRAINT ck_setting_candidates_book_source CHECK (
    (source_kind='EPISODE' AND source_setting_book_id IS NULL AND source_setting_book_revision_id IS NULL)
    OR (source_kind='SETTING_BOOK' AND episode_id IS NULL AND source_setting_book_id IS NOT NULL
        AND source_setting_book_revision_id IS NOT NULL)
);
ALTER TABLE world_setting_candidates ALTER COLUMN source_episode_id DROP NOT NULL;
ALTER TABLE world_setting_candidates ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT 'EPISODE' CHECK (source_kind IN ('EPISODE','SETTING_BOOK'));
ALTER TABLE world_setting_candidates ADD COLUMN source_setting_book_id UUID REFERENCES upload_files(id);
ALTER TABLE world_setting_candidates ADD COLUMN source_setting_book_revision_id UUID REFERENCES setting_book_revisions(id);
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_candidates_book_source CHECK (
    (source_kind='EPISODE' AND source_setting_book_id IS NULL AND source_setting_book_revision_id IS NULL AND source_episode_id IS NOT NULL)
    OR (source_kind='SETTING_BOOK' AND source_episode_id IS NULL AND source_setting_book_id IS NOT NULL
        AND source_setting_book_revision_id IS NOT NULL)
);
ALTER TABLE world_setting_comparison_batches ALTER COLUMN source_episode_id DROP NOT NULL;
