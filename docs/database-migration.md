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
├── V2__add_validation_reports.sql
└── V3__add_episode_lookup_index.sql
```

- 파일명은 `V{순번}__{snake_case_설명}.sql` 형식을 사용합니다.
- 공유 환경에 적용된 migration은 수정하거나 삭제하지 않습니다.
- 기존 테이블 변경도 이전 파일을 고치지 않고 다음 버전에 `ALTER TABLE`을 추가합니다.
- JPA Entity나 Python 공유 모델을 변경하면 migration과 매핑을 같은 작업에서 함께 검토합니다.
- PostgreSQL 전용 타입, 제약조건, 인덱스는 migration에 명시합니다.

## V1 기준

V1은 현재 Java Entity 전체와 Python Worker가 관리하는 `episode_chunks`를 생성합니다.

`episode_chunks`의 임베딩 계약은 다음과 같습니다.

| 항목 | 값 |
| --- | --- |
| 모델 | `text-embedding-3-small` |
| 차원 | 1536 |
| 거리 기준 | cosine distance |
| 인덱스 | HNSW + `vector_cosine_ops` |
| 미생성 상태 | `embedding`과 관련 메타데이터가 모두 `NULL` |

`embedding_model`, `embedding_version`, `embedded_at`은 모델 또는 생성 로직 변경 시 재생성 대상을 판별하기 위한 메타데이터입니다.

## 로컬 검증

빈 PostgreSQL에서 Backend를 시작한 뒤 다음 내용을 확인합니다.

- Flyway 로그에 V1 적용 성공이 출력됩니다.
- `flyway_schema_history`에 version 1이 성공으로 기록됩니다.
- `vector` extension이 활성화됩니다.
- `episode_chunks.embedding`이 `vector(1536)`으로 생성됩니다.
- cosine HNSW 인덱스가 생성됩니다.
- Hibernate schema validation을 통과하고 Backend가 정상 시작됩니다.
- Backend를 재시작해도 V1이 중복 적용되지 않습니다.

## 최초 운영 전환

Flyway 도입 전에 JPA가 만든 운영 테스트 DB에는 `flyway_schema_history`가 없습니다. 이 상태에서 V1을 자동 baseline 처리하면 V1이 건너뛰어져 `episode_chunks` 등 신규 schema가 생성되지 않으므로 `baseline-on-migrate`를 사용하지 않습니다.

현재 데이터가 테스트용이라는 팀 합의를 전제로 다음 순서로 한 번 초기화합니다.

1. 필요한 데이터가 없는지 확인하고 필요하면 `pg_dump`로 백업합니다.
2. Backend와 AI Worker를 중지합니다.
3. PostgreSQL 데이터 volume만 제거하고 빈 PostgreSQL 16 DB를 시작합니다.
4. Backend를 시작해 Flyway V1과 Hibernate validation 성공을 확인합니다.
5. DB schema와 Swagger 기본 API를 확인한 뒤 AI Worker를 시작합니다.

실제 사용자 데이터가 생긴 뒤에는 이 초기화 절차를 사용하지 않습니다. 기존 데이터를 보존하는 V2 이상의 `ALTER` migration과 사전 백업·롤백 계획을 별도로 작성합니다.
