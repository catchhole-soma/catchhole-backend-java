-- 이름이 같다는 이유로 합치지 않는다. 사용자가 선택한 후보의 표시 정체성만 공유한다.
CREATE TABLE relationship_mention_groups (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    disbanded_from_version BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_relationship_mention_groups_work FOREIGN KEY (work_id) REFERENCES works(id),
    CONSTRAINT uk_relationship_mention_groups_request UNIQUE (work_id, request_id),
    CONSTRAINT ck_relationship_mention_groups_name CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_relationship_mention_groups_version CHECK (version >= 0),
    CONSTRAINT ck_relationship_mention_groups_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_relationship_mention_groups_decision CHECK (
        (active AND disbanded_from_version IS NULL)
        OR (NOT active AND disbanded_from_version >= 0 AND version = disbanded_from_version + 1)
    )
);
CREATE INDEX idx_relationship_mention_groups_work ON relationship_mention_groups(work_id);

ALTER TABLE relationship_candidates ADD COLUMN mention_group_id UUID;
ALTER TABLE relationship_candidates ADD CONSTRAINT fk_relationship_candidates_mention_group
    FOREIGN KEY (mention_group_id) REFERENCES relationship_mention_groups(id);
ALTER TABLE relationship_candidates ADD CONSTRAINT ck_relationship_candidates_mention_group
    CHECK (mention_group_id IS NULL OR (status = 'PENDING' AND object_character_id IS NULL));
CREATE INDEX idx_relationship_candidates_mention_group ON relationship_candidates(mention_group_id);
