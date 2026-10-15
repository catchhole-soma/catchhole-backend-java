ALTER TABLE setting_candidates ADD COLUMN narrative_time JSONB;
ALTER TABLE character_facts ADD COLUMN narrative_time JSONB;
ALTER TABLE characters ADD COLUMN current_narrative_time JSONB;
