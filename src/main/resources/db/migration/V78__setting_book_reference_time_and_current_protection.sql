ALTER TABLE setting_book_revisions
    ADD COLUMN reference_time_kind varchar(20) NOT NULL DEFAULT 'UNSPECIFIED',
    ADD COLUMN reference_episode_no integer;

ALTER TABLE setting_book_revisions ADD CONSTRAINT ck_setting_book_reference_time
    CHECK ((reference_time_kind = 'EPISODE' AND reference_episode_no IS NOT NULL AND reference_episode_no > 0)
        OR (reference_time_kind IN ('UNSPECIFIED', 'WORK_START') AND reference_episode_no IS NULL));

ALTER TABLE characters ADD COLUMN manually_edited_slots jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE characters ADD CONSTRAINT ck_characters_manually_edited_slots CHECK (jsonb_typeof(manually_edited_slots) = 'array');
ALTER TABLE setting_candidates ADD COLUMN effective_from_episode_no integer;
ALTER TABLE setting_candidates ADD CONSTRAINT ck_setting_candidate_effective_episode CHECK (effective_from_episode_no IS NULL OR effective_from_episode_no > 0);
ALTER TABLE world_setting_candidates ADD COLUMN temporal_scope varchar(30), ADD COLUMN effective_from_episode_no integer;
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_candidate_temporal_scope CHECK (temporal_scope IS NULL OR temporal_scope IN ('PRESENT','PAST','HYPOTHETICAL','UNKNOWN'));
ALTER TABLE world_setting_candidates ADD CONSTRAINT ck_world_candidate_effective_episode CHECK (effective_from_episode_no IS NULL OR effective_from_episode_no > 0);
