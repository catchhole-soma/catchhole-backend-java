ALTER TABLE character_facts
    ADD CONSTRAINT uk_character_facts_character_slot_id
        UNIQUE (character_id, fact_type, fact_key, id);

ALTER TABLE character_snapshot_sources
    DROP CONSTRAINT fk_character_snapshot_sources_character_fact,
    ADD CONSTRAINT fk_character_snapshot_sources_character_fact
        FOREIGN KEY (character_id, fact_type, fact_key, source_fact_id)
        REFERENCES character_facts (character_id, fact_type, fact_key, id)
        ON DELETE CASCADE;

-- V22의 same-slot FK는 위 4-column unique를 사용하므로 V20의 2-column 보조 unique는 더 이상 필요하지 않다.
ALTER TABLE character_facts
    DROP CONSTRAINT uk_character_facts_character_id_id;
