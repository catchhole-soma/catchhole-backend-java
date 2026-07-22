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
    (gen_random_uuid(), NULL, 'profile', NULL, '프로필', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '[]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.gender', NULL, '성별', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '["성별","gender"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.species', NULL, '종족', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '["종족","species","race"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.affiliation', NULL, '소속', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '["소속","affiliation"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.occupation', NULL, '직업', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '["직업","occupation","job"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.eye_color', NULL, '눈 색깔', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '["눈 색깔","눈색깔","eye_color"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.description', NULL, '설명', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '["설명","description"]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (gen_random_uuid(), NULL, 'profile.attribute', 'profile.*', '프로필', 'PROFILE', 'STRING', 'BASE_VALUE', 'REPLACE', '[]'::jsonb, 'SYSTEM_SEED', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
