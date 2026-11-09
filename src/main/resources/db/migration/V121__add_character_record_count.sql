-- Fact가 집계의 단일 출처다. 마이그레이션 중 쓰기를 막아 초기값과 트리거 경계의 누락을 방지한다.
LOCK TABLE character_facts IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE characters ADD COLUMN record_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE characters ADD CONSTRAINT ck_characters_record_count CHECK (record_count >= 0);
UPDATE characters c SET record_count = f.n
FROM (SELECT character_id, count(*) AS n FROM character_facts GROUP BY character_id) f
WHERE c.id = f.character_id;

CREATE INDEX idx_characters_work_status_records_id ON characters(work_id, status, record_count DESC, id DESC);
CREATE INDEX idx_character_discovery_alias_lookup ON setting_candidates(work_id, matched_character_id, lower(entity_name))
WHERE candidate_kind = 'CHARACTER_DISCOVERY' AND review_status = 'CONFIRMED' AND matched_character_id IS NOT NULL;

-- 문장별로 인물당 한 번만 갱신하며 여러 인물을 건드릴 때의 잠금 순서도 고정한다.
-- character의 updated_at/snapshot_version을 바꾸지 않으며 ORM은 이 컬럼을 쓰지 않는다.
CREATE FUNCTION increment_character_record_counts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE delta record;
BEGIN
    FOR delta IN SELECT character_id, count(*) AS n FROM inserted_facts GROUP BY character_id ORDER BY character_id LOOP
        UPDATE characters SET record_count = record_count + delta.n WHERE id = delta.character_id;
    END LOOP;
    RETURN NULL;
END $$;
CREATE FUNCTION decrement_character_record_counts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE delta record;
BEGIN
    FOR delta IN SELECT character_id, count(*) AS n FROM deleted_facts GROUP BY character_id ORDER BY character_id LOOP
        UPDATE characters SET record_count = record_count - delta.n WHERE id = delta.character_id;
    END LOOP;
    RETURN NULL;
END $$;
CREATE FUNCTION move_character_record_counts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE delta record;
BEGIN
    FOR delta IN
        SELECT character_id, sum(n) AS n FROM (
            SELECT character_id, count(*) AS n FROM new_facts GROUP BY character_id
            UNION ALL SELECT character_id, -count(*) AS n FROM old_facts GROUP BY character_id
        ) changes GROUP BY character_id HAVING sum(n) <> 0 ORDER BY character_id
    LOOP
        UPDATE characters SET record_count = record_count + delta.n WHERE id = delta.character_id;
    END LOOP;
    RETURN NULL;
END $$;
CREATE TRIGGER character_records_insert AFTER INSERT ON character_facts
REFERENCING NEW TABLE AS inserted_facts FOR EACH STATEMENT EXECUTE FUNCTION increment_character_record_counts();
CREATE TRIGGER character_records_delete AFTER DELETE ON character_facts
REFERENCING OLD TABLE AS deleted_facts FOR EACH STATEMENT EXECUTE FUNCTION decrement_character_record_counts();
CREATE TRIGGER character_records_move AFTER UPDATE ON character_facts
REFERENCING OLD TABLE AS old_facts NEW TABLE AS new_facts FOR EACH STATEMENT EXECUTE FUNCTION move_character_record_counts();
