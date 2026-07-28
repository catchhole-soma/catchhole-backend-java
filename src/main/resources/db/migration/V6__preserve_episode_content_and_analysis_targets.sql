ALTER TABLE episodes
    ADD COLUMN content_updated_at TIMESTAMP;

UPDATE episodes
SET content_updated_at = COALESCE(
    (
        SELECT upload_files.created_at
        FROM upload_files
        WHERE upload_files.id = episodes.source_file_id
    ),
    episodes.created_at
);

ALTER TABLE episodes
    ALTER COLUMN content_updated_at SET NOT NULL;

CREATE TABLE analysis_job_episode_targets (
    analysis_job_id UUID NOT NULL,
    episode_id UUID NOT NULL,
    PRIMARY KEY (analysis_job_id, episode_id),
    CONSTRAINT fk_analysis_job_episode_targets_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE CASCADE,
    CONSTRAINT fk_analysis_job_episode_targets_episode
        FOREIGN KEY (episode_id) REFERENCES episodes (id)
);

CREATE INDEX idx_analysis_job_episode_targets_episode
    ON analysis_job_episode_targets (episode_id);

INSERT INTO analysis_job_episode_targets (analysis_job_id, episode_id)
SELECT analysis_jobs.id, analysis_jobs.episode_id
FROM analysis_jobs
WHERE analysis_jobs.episode_id IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO analysis_job_episode_targets (analysis_job_id, episode_id)
SELECT analysis_jobs.id, episodes.id
FROM analysis_jobs
JOIN upload_files
    ON upload_files.batch_id = analysis_jobs.batch_id
JOIN episodes
    ON episodes.source_file_id = upload_files.id
WHERE analysis_jobs.episode_id IS NULL
ON CONFLICT DO NOTHING;
