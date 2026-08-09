ALTER TABLE world_setting_candidates
    ADD COLUMN consolidation_status VARCHAR(20) NOT NULL DEFAULT 'SINGLE';

ALTER TABLE world_setting_candidates
    ADD CONSTRAINT ck_world_setting_candidates_consolidation_status
        CHECK (consolidation_status IN ('SINGLE', 'MERGED', 'CONFLICT'));
