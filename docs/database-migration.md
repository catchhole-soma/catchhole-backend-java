# Database Migration

PostgreSQL schema는 Spring Backend의 Flyway migration을 단일 변경 주체로 관리합니다. JPA와 Python SQLAlchemy는 Flyway가 만든 schema에 맞춰 매핑하며 테이블이나 컬럼을 자동 생성하지 않습니다.

## 실행 순서

Backend 시작 시 다음 순서로 DB를 확인합니다.

1. Flyway가 `db/migration`에서 아직 적용되지 않은 SQL을 실행합니다.
2. 적용 결과와 checksum을 `flyway_schema_history`에 기록합니다.
3. Hibernate가 `ddl-auto=validate`로 JPA Entity와 schema를 비교합니다.
4. migration 실패나 JPA 불일치가 있으면 Backend 시작을 실패시킵니다.

H2 기반 테스트는 PostgreSQL의 `vector` 타입을 지원하지 않으므로 Flyway를 끄고 기존 `create-drop` 방식을 유지합니다. migration 검증은 실제 pgvector PostgreSQL에서 별도로 수행합니다.

## 파일 작성 규칙

```text
src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__add_character_setting_schema_registry.sql
└── V3__link_character_facts_to_setting_candidates.sql
```

- 파일명은 `V{순번}__{snake_case_설명}.sql` 형식을 사용합니다.
- 공유 환경에 적용된 migration은 수정하거나 삭제하지 않습니다.
- 기존 테이블 변경도 이전 파일을 고치지 않고 다음 버전에 `ALTER TABLE`을 추가합니다.
- JPA Entity나 Python 공유 모델을 변경하면 migration과 매핑을 같은 작업에서 함께 검토합니다.
- PostgreSQL 전용 타입, 제약조건, 인덱스는 migration에 명시합니다.

## V1 기준

V1은 Flyway 도입 시점의 Java Entity와 Python Worker가 관리하는 `episode_chunks`를 생성합니다. 이후 추가된 Entity는 V2 이상의 migration에서 누적합니다.

`episode_chunks`의 임베딩 계약은 다음과 같습니다.

| 항목 | 값 |
| --- | --- |
| 모델 | `text-embedding-3-small` |
| 차원 | 1536 |
| 거리 기준 | cosine distance |
| 인덱스 | HNSW + `vector_cosine_ops` |
| 미생성 상태 | `embedding`과 관련 메타데이터가 모두 `NULL` |

`embedding_model`, `embedding_version`, `embedded_at`은 모델 또는 생성 로직 변경 시 재생성 대상을 판별하기 위한 메타데이터입니다.
청크의 `created_at`, `updated_at`은 Python Worker가 항상 기록하며 nullable로 두지 않습니다.

## V2 기준

V2는 AI의 `SettingCandidate.attributeName`을 canonical key로 해석하기 위한 `character_setting_schemas` registry를 추가합니다. 실제 캐릭터 능력치 값이나 작품 내용은 저장하지 않습니다.

- `work_id = NULL`인 row는 전역 schema이고, 값이 있으면 해당 작품에 전역에 없는 key를 추가하는 schema입니다.
- 전역 `schema_key`와 작품별 `work_id + schema_key`는 partial unique index로 각각 중복을 막습니다.
- `aliases_json`은 JSON 배열만 허용하고, 활성 조회에는 `(work_id, enabled, schema_key)` 인덱스를 사용합니다.
- 초기 전역 seed는 공통 `SYSTEM_SEED` 7개와 판타지 POC `DEV_SEED` 15개로 구성합니다.
- 적용된 seed나 alias를 변경할 때는 V2를 수정하지 않고 다음 migration에서 `UPDATE`합니다.

## V3 기준

V3는 confirm으로 생성된 `character_facts`에서 원본 후보의 `evidence_spans`를 정확히 역추적할 수 있도록 nullable `setting_candidate_id` FK와 조회 인덱스를 추가합니다.

- V3 이후 confirm Fact는 `setting_candidate_id`를 채웁니다.
- 기존 Fact는 후보를 휴리스틱하게 추정하지 않고 `NULL`로 유지합니다.
- 근거 인용문과 offset JSON은 `setting_candidates.evidence_spans`를 단일 저장 위치로 사용하고 Fact에 중복 저장하지 않습니다.
- FK의 기본 `NO ACTION`으로 근거 후보가 연결된 채 임의 삭제되는 것을 막습니다.

## 논리 참조와 FK 기준

ID 컬럼이 다른 테이블을 논리적으로 가리키더라도 삭제·재처리 정책이 정해지지 않았다면 FK를 먼저 강제하지 않습니다. V1의 선택은 다음과 같습니다.

| 컬럼 | 참조 대상 | V1 정책 | 이유 및 후속 논의 |
| --- | --- | --- | --- |
| `episodes.source_file_id` | `upload_files.id` | nullable FK, 기본 `NO ACTION` | 업로드 파일을 먼저 저장한 뒤 회차를 생성하고 현재 업로드 파일 삭제 API가 없으므로 원본 추적 무결성을 강제합니다. 삭제 기능을 추가하면 연결된 회차 처리 정책을 함께 정합니다. |
| `characters.first_appearance_episode_id` | `episodes.id` | FK 보류 | 현재 회차 삭제는 `ARCHIVED` soft delete이며, 향후 물리 삭제 시 최초 등장 회차를 재계산할지 `NULL`로 둘지 먼저 결정해야 합니다. |
| `setting_candidates.source_chunk_id` | `episode_chunks.id` | FK 보류 | 재청킹이 기존 청크를 삭제하고 새 UUID로 교체하므로 일반 FK는 재청킹을 막고, cascade 또는 set null은 근거를 손실할 수 있습니다. |
| `character_facts.source_chunk_id` | `episode_chunks.id` | FK 보류 | 확정 설정의 원문 근거이므로 청크 ID 안정화 또는 청크 이력 보존 정책을 정한 뒤 FK를 검토합니다. |

FK를 보류한 컬럼도 임의 UUID 용도가 아니라 위 참조 대상을 저장하는 논리 연결입니다. 후속 정책이 정해지면 새 migration에서 제약조건과 삭제 동작을 추가합니다.

## 로컬 검증

V1·V2 적용 DB에 현재 Backend를 시작해 V3만 추가 적용되는 경로와, 빈 PostgreSQL에서 V1→V2→V3가 순서대로 적용되는 경로를 각각 확인합니다.

- Flyway 로그에 V1부터 V3까지 적용 성공이 출력됩니다.
- `flyway_schema_history`에 version 1, 2, 3이 성공으로 기록됩니다.
- `vector` extension이 활성화됩니다.
- `episode_chunks.embedding`이 `vector(1536)`으로 생성됩니다.
- cosine HNSW 인덱스가 생성됩니다.
- `character_setting_schemas`에 전역 seed 22개(`SYSTEM_SEED=7`, `DEV_SEED=15`)가 생성됩니다.
- `character_facts.setting_candidate_id`와 FK·조회 인덱스가 생성됩니다.
- Hibernate schema validation을 통과하고 Backend가 정상 시작됩니다.
- Backend를 재시작해도 V1부터 V3까지 중복 적용되지 않습니다.

## 최초 운영 전환

Flyway 도입 전에 JPA가 만든 운영 테스트 DB에는 `flyway_schema_history`가 없습니다. 이 상태에서 V1을 자동 baseline 처리하면 V1이 건너뛰어져 `episode_chunks` 등 신규 schema가 생성되지 않으므로 `baseline-on-migrate`를 사용하지 않습니다.

현재 데이터가 테스트용이라는 팀 합의를 전제로 다음 순서로 한 번 초기화합니다.

1. 필요한 데이터가 없는지 확인하고 필요하면 `pg_dump`로 백업합니다.
2. Backend와 AI Worker를 중지합니다.
3. PostgreSQL 데이터 volume만 제거하고 빈 PostgreSQL 16 DB를 시작합니다.
4. Backend를 시작해 Flyway V1~V3와 Hibernate validation 성공을 확인합니다.
5. DB schema와 Swagger 기본 API를 확인한 뒤 AI Worker를 시작합니다.

실제 사용자 데이터가 생긴 뒤에는 이 초기화 절차를 사용하지 않습니다. 기존 데이터를 보존하는 V2 이상의 `ALTER` migration과 사전 백업·롤백 계획을 별도로 작성합니다.
