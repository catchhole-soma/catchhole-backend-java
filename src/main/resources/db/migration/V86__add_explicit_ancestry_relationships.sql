-- 명시된 조상·후손은 중간 인물·부모 간선을 생성하지 않는 독립 관계다.
-- generation_gap은 N대손의 N만 보존한다. 미상은 NULL이며 기존 관계 identity는 유지한다.
ALTER TABLE character_relationships ADD COLUMN generation_gap INTEGER;
ALTER TABLE relationship_candidates ADD COLUMN generation_gap INTEGER;

ALTER TABLE character_relationships DROP CONSTRAINT ck_character_relationships_kind;
ALTER TABLE character_relationships ADD CONSTRAINT ck_character_relationships_kind
    CHECK (kind IN ('PARENT_OF', 'ANCESTOR_OF', 'SPOUSE', 'SIBLING'));
ALTER TABLE character_relationships DROP CONSTRAINT ck_character_relationships_subtype;
ALTER TABLE character_relationships ADD CONSTRAINT ck_character_relationships_subtype CHECK (
    (kind = 'PARENT_OF' AND subtype IN ('UNSPECIFIED', 'BIOLOGICAL', 'ADOPTIVE', 'STEP'))
    OR (kind = 'ANCESTOR_OF' AND subtype IN ('UNSPECIFIED', 'BIOLOGICAL'))
    OR (kind = 'SPOUSE' AND subtype = 'UNSPECIFIED')
    OR (kind = 'SIBLING' AND subtype IN ('UNSPECIFIED', 'BIOLOGICAL', 'ADOPTIVE', 'STEP', 'FULL', 'HALF'))
);
ALTER TABLE character_relationships ADD CONSTRAINT ck_character_relationships_generation_gap
    CHECK (generation_gap IS NULL OR (kind = 'ANCESTOR_OF' AND generation_gap > 0));

ALTER TABLE relationship_candidates DROP CONSTRAINT ck_relationship_candidates_kind;
ALTER TABLE relationship_candidates ADD CONSTRAINT ck_relationship_candidates_kind
    CHECK (kind IN ('PARENT_OF', 'CHILD_OF', 'ANCESTOR_OF', 'DESCENDANT_OF', 'SPOUSE', 'SIBLING'));
ALTER TABLE relationship_candidates ADD CONSTRAINT ck_relationship_candidates_ancestry_subtype
    CHECK (kind NOT IN ('ANCESTOR_OF', 'DESCENDANT_OF') OR subtype IN ('UNSPECIFIED', 'BIOLOGICAL'));
ALTER TABLE relationship_candidates ADD CONSTRAINT ck_relationship_candidates_generation_gap
    CHECK (generation_gap IS NULL OR (kind IN ('ANCESTOR_OF', 'DESCENDANT_OF') AND generation_gap > 0));
