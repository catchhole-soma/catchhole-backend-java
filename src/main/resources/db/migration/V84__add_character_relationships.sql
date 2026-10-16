-- 현재 캐릭터 설정에서 확인된 가족 관계와 검토 후보를 분리한다.
-- 근거 원문은 복사하지 않고 기존 CharacterFact와 확인 당시 슬롯 토큰만 연결한다.
CREATE TABLE character_relationships (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    subject_character_id UUID NOT NULL,
    object_character_id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL,
    subtype VARCHAR(30) NOT NULL,
    temporal_scope VARCHAR(30) NOT NULL,
    time_label VARCHAR(200),
    canonical_key VARCHAR(64) NOT NULL,
    withdrawn BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_character_relationships_work FOREIGN KEY (work_id) REFERENCES works(id),
    CONSTRAINT fk_character_relationships_subject FOREIGN KEY (subject_character_id) REFERENCES characters(id),
    CONSTRAINT fk_character_relationships_object FOREIGN KEY (object_character_id) REFERENCES characters(id),
    CONSTRAINT ck_character_relationships_distinct_endpoints CHECK (subject_character_id <> object_character_id),
    CONSTRAINT ck_character_relationships_kind CHECK (kind IN ('PARENT_OF', 'SPOUSE', 'SIBLING')),
    CONSTRAINT ck_character_relationships_subtype CHECK (
        (kind = 'PARENT_OF' AND subtype IN ('UNSPECIFIED', 'BIOLOGICAL', 'ADOPTIVE', 'STEP'))
        OR (kind = 'SPOUSE' AND subtype = 'UNSPECIFIED')
        OR (kind = 'SIBLING' AND subtype IN ('UNSPECIFIED', 'BIOLOGICAL', 'ADOPTIVE', 'STEP', 'FULL', 'HALF'))
    ),
    CONSTRAINT ck_character_relationships_temporal_scope CHECK (temporal_scope IN ('CURRENT', 'HISTORICAL', 'UNKNOWN')),
    CONSTRAINT ck_character_relationships_key CHECK (canonical_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_character_relationships_version CHECK (version >= 0)
);

-- 철회된 관계 이력은 보존하되 동일 내용을 다시 확인한 새 관계를 막지 않는다.
CREATE UNIQUE INDEX uk_character_relationships_current_key
    ON character_relationships(work_id, canonical_key) WHERE withdrawn = FALSE;
CREATE INDEX idx_character_relationships_work_created ON character_relationships(work_id, created_at, id);
CREATE INDEX idx_character_relationships_subject ON character_relationships(subject_character_id);
CREATE INDEX idx_character_relationships_object ON character_relationships(object_character_id);

CREATE TABLE relationship_candidates (
    id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    subject_character_id UUID NOT NULL,
    object_character_id UUID,
    object_mention VARCHAR(200),
    kind VARCHAR(30) NOT NULL,
    subtype VARCHAR(30) NOT NULL,
    temporal_scope VARCHAR(30) NOT NULL,
    time_label VARCHAR(200),
    assertion VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    confirmed_relationship_id UUID,
    replaces_relationship_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    decision_from_version BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_relationship_candidates_work FOREIGN KEY (work_id) REFERENCES works(id),
    CONSTRAINT fk_relationship_candidates_subject FOREIGN KEY (subject_character_id) REFERENCES characters(id),
    CONSTRAINT fk_relationship_candidates_object FOREIGN KEY (object_character_id) REFERENCES characters(id),
    CONSTRAINT fk_relationship_candidates_confirmed FOREIGN KEY (confirmed_relationship_id) REFERENCES character_relationships(id),
    CONSTRAINT fk_relationship_candidates_replaces FOREIGN KEY (replaces_relationship_id) REFERENCES character_relationships(id),
    CONSTRAINT uk_relationship_candidates_request UNIQUE (work_id, request_id),
    CONSTRAINT ck_relationship_candidates_kind CHECK (kind IN ('PARENT_OF', 'CHILD_OF', 'SPOUSE', 'SIBLING')),
    CONSTRAINT ck_relationship_candidates_subtype CHECK (subtype IN ('UNSPECIFIED', 'BIOLOGICAL', 'ADOPTIVE', 'STEP', 'FULL', 'HALF')),
    CONSTRAINT ck_relationship_candidates_temporal_scope CHECK (temporal_scope IN ('CURRENT', 'HISTORICAL', 'UNKNOWN')),
    CONSTRAINT ck_relationship_candidates_assertion CHECK (assertion IN ('ASSERTED', 'UNCERTAIN', 'NEGATED', 'FIGURATIVE', 'CONFLICTING')),
    CONSTRAINT ck_relationship_candidates_status CHECK (status IN ('PENDING', 'CONFIRMED', 'DISMISSED')),
    CONSTRAINT ck_relationship_candidates_decision CHECK (
        (status = 'PENDING' AND confirmed_relationship_id IS NULL AND decision_from_version IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_relationship_id IS NOT NULL AND decision_from_version IS NOT NULL)
        OR (status = 'DISMISSED' AND confirmed_relationship_id IS NULL AND decision_from_version IS NOT NULL)
    ),
    CONSTRAINT ck_relationship_candidates_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_relationship_candidates_version CHECK (
        version >= 0 AND (decision_from_version IS NULL OR (decision_from_version >= 0 AND version = decision_from_version + 1))
    )
);

CREATE INDEX idx_relationship_candidates_work_created ON relationship_candidates(work_id, created_at, id);
CREATE INDEX idx_relationship_candidates_confirmed ON relationship_candidates(confirmed_relationship_id);
CREATE INDEX idx_relationship_candidates_replaces ON relationship_candidates(replaces_relationship_id);
CREATE INDEX idx_relationship_candidates_subject ON relationship_candidates(subject_character_id);
CREATE INDEX idx_relationship_candidates_object ON relationship_candidates(object_character_id);

CREATE TABLE relationship_evidence (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL,
    source_fact_id UUID NOT NULL,
    source_token VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_relationship_evidence_candidate FOREIGN KEY (candidate_id) REFERENCES relationship_candidates(id),
    CONSTRAINT fk_relationship_evidence_source_fact FOREIGN KEY (source_fact_id) REFERENCES character_facts(id),
    CONSTRAINT uk_relationship_evidence_candidate_fact UNIQUE (candidate_id, source_fact_id),
    CONSTRAINT ck_relationship_evidence_source_token CHECK (source_token ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_relationship_evidence_source_fact ON relationship_evidence(source_fact_id);
