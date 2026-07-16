CREATE TABLE character_setting_schemas (
    id UUID PRIMARY KEY,
    work_id UUID,
    schema_key VARCHAR(100) NOT NULL,
    attribute_pattern VARCHAR(100),
    display_name VARCHAR(100) NOT NULL,
    fact_type VARCHAR(30) NOT NULL,
    value_type VARCHAR(30) NOT NULL,
    value_semantics VARCHAR(30) NOT NULL,
    merge_policy VARCHAR(30) NOT NULL,
    aliases_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    source VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_character_setting_schemas_work
        FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT ck_character_setting_schemas_aliases_array
        CHECK (jsonb_typeof(aliases_json) = 'array')
);

CREATE UNIQUE INDEX uk_character_setting_schemas_global_key
    ON character_setting_schemas (schema_key)
    WHERE work_id IS NULL;
CREATE UNIQUE INDEX uk_character_setting_schemas_work_key
    ON character_setting_schemas (work_id, schema_key)
    WHERE work_id IS NOT NULL;
CREATE INDEX idx_character_setting_schemas_active_lookup
    ON character_setting_schemas (work_id, enabled, schema_key);

INSERT INTO character_setting_schemas (
    id,
    work_id,
    schema_key,
    attribute_pattern,
    display_name,
    fact_type,
    value_type,
    value_semantics,
    merge_policy,
    aliases_json,
    source,
    enabled,
    created_at,
    updated_at
) VALUES
    (gen_random_uuid(), NULL, 'age', NULL, '나이', 'AGE', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["나이"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'level', NULL, '레벨', 'LEVEL', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["레벨"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.strength', NULL, '근력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["근력","힘","str","strength"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.mana', NULL, '마나', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["마나","mana","mp"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'statuses.condition', 'status.*', '상태', 'STATUS', 'JSON', 'BASE_VALUE', 'UPSERT_BY_NAME', '[]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'skills.skill', 'skill.*', '스킬', 'SKILL', 'JSON', 'BASE_VALUE', 'UPSERT_BY_NAME', '[]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'items.item', 'item.*', '아이템', 'ITEM', 'JSON', 'BASE_VALUE', 'UPSERT_BY_NAME', '[]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.physique', NULL, '육체', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["육체","physical","physique"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.mental', NULL, '정신', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["정신","mental"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.supernatural', NULL, '이능', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["이능","supernatural"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.item_level', NULL, '아이템 레벨', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["아이템 레벨","아이템레벨","item_level"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.combat_power', NULL, '전투지수', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["전투지수","전투력","combat_power"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.agility', NULL, '민첩성', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["민첩","민첩성","agility","dexterity","dex"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.endurance', NULL, '지구력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["지구력","endurance","stamina"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.soul_power', NULL, '영혼력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["영혼력","soul_power"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.magic_resistance', NULL, '항마력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["항마력","마법저항","마법 저항","magic_resistance"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.physical_resistance', NULL, '물리내성', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["물리내성","물리저항","물리 저항","physical_resistance"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.natural_regeneration', NULL, '자연재생력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["자연재생력","자연재생","자연 재생","natural_regeneration"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.bone_strength', NULL, '골강도', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["골강도","bone_strength"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.energy', NULL, '기력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["기력","energy"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.perception', NULL, '인지력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["인지력","perception"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'stats.mental_power', NULL, '정신력', 'STAT', 'NUMBER', 'BASE_VALUE', 'REPLACE', '["정신력","mental_power"]'::jsonb, 'DEV_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
