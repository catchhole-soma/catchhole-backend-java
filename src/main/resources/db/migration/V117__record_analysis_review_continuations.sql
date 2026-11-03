CREATE TABLE analysis_review_continuations (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL,
    review_revision VARCHAR(64) NOT NULL,
    first_episode_no INTEGER NOT NULL,
    character_count BIGINT NOT NULL,
    world_count BIGINT NOT NULL,
    relationship_count BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_analysis_review_continuations_work ON analysis_review_continuations(work_id);
