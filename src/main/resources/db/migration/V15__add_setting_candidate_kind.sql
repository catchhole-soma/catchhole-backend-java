ALTER TABLE setting_candidates
    ADD COLUMN candidate_kind VARCHAR(30) NOT NULL DEFAULT 'SETTING';

ALTER TABLE setting_candidates
    ALTER COLUMN attribute_name DROP NOT NULL,
    ALTER COLUMN value_type DROP NOT NULL;

ALTER TABLE setting_candidates
    ADD CONSTRAINT ck_setting_candidates_kind_payload
        CHECK (
            (
                candidate_kind = 'SETTING'
                AND attribute_name IS NOT NULL
                AND value_type IS NOT NULL
            )
            OR
            (
                candidate_kind = 'CHARACTER_DISCOVERY'
                AND attribute_name IS NULL
                AND attribute_value IS NULL
                AND value_type IS NULL
                AND value_json IS NULL
            )
        );
