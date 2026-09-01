ALTER TABLE world_setting_comparison_decisions
    ADD COLUMN existing_root_property_move_snapshots JSONB NOT NULL DEFAULT '[]'::JSONB,
    ADD CONSTRAINT ck_world_setting_comparison_decisions_root_move_snapshots
        CHECK (jsonb_typeof(existing_root_property_move_snapshots) = 'array');
