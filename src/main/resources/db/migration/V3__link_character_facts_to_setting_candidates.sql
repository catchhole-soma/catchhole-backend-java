ALTER TABLE character_facts
    ADD COLUMN setting_candidate_id UUID;

ALTER TABLE character_facts
    ADD CONSTRAINT fk_character_facts_setting_candidate
        FOREIGN KEY (setting_candidate_id) REFERENCES setting_candidates (id);

CREATE INDEX idx_character_facts_setting_candidate
    ON character_facts (setting_candidate_id);
