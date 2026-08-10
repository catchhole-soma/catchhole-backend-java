ALTER TABLE world_setting_candidates
    ADD COLUMN scope_name VARCHAR(100),
    ADD COLUMN proposed_scope_name VARCHAR(100),
    ADD COLUMN final_scope_name VARCHAR(100);
