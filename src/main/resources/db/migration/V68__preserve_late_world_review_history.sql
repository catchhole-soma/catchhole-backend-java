ALTER TABLE world_setting_candidates ADD COLUMN history_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE world_settings ADD COLUMN manually_edited_paths JSONB NOT NULL DEFAULT '[]';
CREATE INDEX idx_world_candidate_path_history ON world_setting_candidates
    (target_world_setting_id, final_setting_name, final_scope_name, source_episode_id)
    WHERE review_status = 'CONFIRMED' AND history_only = FALSE;
