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
├── V3__link_character_facts_to_setting_candidates.sql
├── V4__normalize_work_metadata.sql
├── V5__add_upload_file_archive.sql
├── V6__preserve_episode_content_and_analysis_targets.sql
├── V7__separate_setting_book_editable_content.sql
├── V8__add_character_profile_setting_schemas.sql
├── V9__add_character_page_index.sql
├── V10__add_analysis_batch_page_indexes.sql
├── V11__add_character_stat_pattern_schema.sql
├── V12__preserve_setting_candidate_source_content.sql
└── V13__add_ai_token_quota.sql
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

## V4 기준

V4는 자유 문자열이던 `works.genre`를 Java `WorkGenre` enum 저장 규칙과 일치시키고, 작품 설명을 API의 최대 50자 계약에 맞춥니다.

- 기존 한글 장르 값은 `FANTASY`, `ROMANCE` 같은 enum 상수명으로 변환합니다.
- 기존 enum 상수명은 그대로 유지해 migration 재실행 전후 의미가 달라지지 않게 합니다.
- 현재 데이터가 테스트용이라는 합의에 따라 `NULL`과 지원 목록 밖의 문자열은 `ETC`로 정규화합니다.
- 정규화 후 `NOT NULL`과 `chk_works_genre`를 적용해 OpenAPI가 선언한 열 가지 장르 밖의 값이 저장되지 않게 합니다.
- 기존 작품 설명이 50자를 초과하면 앞 50자까지 보존하고 `works.description`을 `VARCHAR(50)`로 변경합니다.

## V5 기준

V5는 설정집 원본 교체 시 기존 `upload_files` 행을 삭제하지 않고 보관할 수 있도록 `archived_at`을 추가합니다.

- 활성 파일은 `archived_at IS NULL`, 교체된 파일은 보관 시각이 기록된 행으로 구분합니다.
- `(batch_id, file_role, archived_at)` 인덱스로 업로드 묶음의 활성 설정집을 조회합니다.
- 기존 업로드 파일은 모두 활성 상태로 유지되도록 `archived_at`을 nullable로 추가합니다.

## V6 기준

V6는 제목 수정과 구분되는 회차 원문 변경 시각과 분석 작업 대상 스냅샷을 추가합니다.

- `episodes.content_updated_at`은 기존 원본 `upload_files.created_at`, 원본 파일이 없으면 `episodes.created_at`으로 backfill한 뒤 `NOT NULL`로 전환합니다.
- 신규·수정 회차는 원문 직접 수정이나 파일 교체 때만 `content_updated_at`을 갱신합니다.
- `analysis_job_episode_targets`는 `(analysis_job_id, episode_id)` 복합 PK로 작업 생성 시점의 실제 대상 회차를 보존합니다.
- 기존 단일 회차 작업은 `analysis_jobs.episode_id`, 기존 배치 작업은 migration 시점의 batch→upload file→episode 관계로 backfill합니다.
- 이후 원본 파일 교체나 회차 보관은 과거 작업의 대상 연결을 삭제하지 않습니다.

## V7 기준

V7은 설정집 업로드 원본과 화면에서 조회·수정하는 현재 텍스트의 저장 위치를 분리합니다.

- `upload_files.content_storage_url`을 nullable `VARCHAR(512)`로 추가합니다.
- 신규 설정집은 원본을 `storage_url`에 보존하고, 추출한 TXT/DOCX 텍스트를 `works/{workId}/setting-books/{settingBookId}/{normalizedOriginalBasename}.txt` 고정 key에 저장합니다.
- 기존 설정집 row는 `content_storage_url=NULL`로 유지하며 조회 시 원본을 파싱하고, 첫 수정부터 고정 key를 연결합니다.
- 수정은 같은 key를 PUT하므로 원본 MIME·크기와 S3 원본 객체를 변경하지 않습니다.

## V8 기준

V8은 캐릭터 상세 조회·수정에서 프로필 값을 다른 설정과 같은 `CharacterFact` 이력과 snapshot 규칙으로 관리할 수 있도록 전역 `SYSTEM_SEED` 프로필 schema 8개를 추가합니다. 테이블이나 컬럼 구조는 변경하지 않습니다.

- 정확 key인 `profile`, `profile.gender`, `profile.species`, `profile.affiliation`, `profile.occupation`, `profile.eye_color`, `profile.description`을 추가합니다.
- `profile.attribute`는 `profile.*` 패턴으로 위 정확 key에 포함되지 않은 작품별 프로필 속성을 `PROFILE` Fact로 분류합니다. Resolver는 정확 key와 alias를 패턴보다 먼저 적용합니다.
- 모든 프로필 schema는 `valueType=STRING`, `valueSemantics=BASE_VALUE`, `mergePolicy=REPLACE`, `source=SYSTEM_SEED`, `enabled=true`입니다.
- V8 적용 후 활성 전역 seed는 `SYSTEM_SEED=15`, `DEV_SEED=15`, 총 30개입니다.
- 기존 V2 seed와 확정 Fact는 수정하거나 backfill하지 않습니다. 이후 새로 확정하거나 사용자가 직접 수정한 `PROFILE` Fact부터 `profile_json` snapshot에 반영합니다.

## V9 기준

V9는 활성 캐릭터 목록의 고정 정렬 페이지 조회를 지원하는 복합 인덱스를 추가합니다.

- 인덱스는 `characters(work_id, status, created_at DESC, id DESC)` 순서입니다.
- 작품과 `ACTIVE` 상태를 먼저 제한한 뒤 `createdAt DESC, id DESC` 정렬을 그대로 사용할 수 있게 합니다.
- 기존 `idx_characters_work_status`는 다른 조회 경로와 적용 환경의 변경 위험을 줄이기 위해 제거하지 않습니다.

## V10 기준

V10은 분석 목록의 업로드 배치 페이지 조회와 배치별 설정 후보 검토 현황 집계를 지원합니다.

- `analysis_jobs(work_id, batch_id, created_at DESC, id DESC)` 인덱스로 작품 안의 배치를 최근 분석 요청순으로 조회하고, 같은 배치의 재시도 이력을 함께 읽습니다.
- `setting_candidates(analysis_job_id, review_status)` 인덱스로 현재 페이지에 포함된 배치의 전체·검토 완료·검토 대기 후보 수를 집계합니다.
- 새 컬럼이나 기존 데이터 변환은 없으며 기존 분석 작업과 설정 후보는 그대로 유지합니다.

## V11 기준

V11은 정확 key나 alias로 등록되지 않은 사용자 정의 스탯을 숫자 Fact로 저장할 수 있도록 전역 `stats.*` 패턴 schema 한 개를 추가합니다.

- `stats.attribute`는 `valueType=NUMBER`, `valueSemantics=BASE_VALUE`, `mergePolicy=REPLACE`, `source=SYSTEM_SEED`, `enabled=true`입니다.
- Resolver는 정확 key와 alias를 패턴보다 먼저 적용하므로 `힘` 같은 기존 alias는 계속 `stats.strength`로 정규화하고, `stats.지능` 같은 사용자 정의 스탯만 입력 key를 보존합니다.
- 기존 schema와 Character Fact는 수정하거나 backfill하지 않습니다.

## V12 기준

V12는 캐릭터 Fact의 원문 근거가 분석 뒤 원고 교체에도 같은 문자열과 offset을 사용하도록
`setting_candidates.source_content_s3_key VARCHAR(512)`를 추가합니다.

- Python Worker는 신규 후보를 저장할 때 claim payload의 분석 당시
  `Episode.content_s3_key`를 함께 기록합니다.
- CharacterFact 근거 API는 후보에 기록된 key를 우선 사용하며 key 자체는 외부에 노출하지
  않습니다.
- V12 이전 후보는 정확한 분석 당시 key를 추정 backfill하지 않고 `NULL`로 유지하며, 조회
  시 현재 Episode key로만 fallback합니다.
- 기존 회차·후보·Fact 행과 S3 객체를 수정하거나 삭제하지 않습니다.

## V13 기준

V13은 AI 요청별 실제 토큰 사용량과 회원의 일회성 기본 사용 한도를 관리하는 세 테이블을 추가합니다.

- `ai_token_accounts`는 회원별 누적 지급량, 실제 사용량, 처리 중 예약량을 한 행에 보관합니다.
- `ai_token_grants`는 기본·운영 지급 이력을 별도로 남기며 월 단위 자동 초기화는 하지 않습니다.
- `ai_token_usages`는 Worker가 생성한 `request_id`를 기준으로 요청 목적, 재시도 차수, 모델, 예약량, 실제 input/cached input/output과 처리 결과를 기록합니다.
- 원고 prompt와 LLM 응답 본문은 사용량 테이블에 저장하지 않습니다.
- `(analysis_job_id, status)`와 `(member_id, created_at DESC)` 인덱스로 작업 합계와 회원 이력을 조회합니다.
- 회원 삭제 시 해당 회원의 계정·지급·사용 이력을 함께 삭제합니다.
- 작품 또는 분석 작업 삭제 시 연결된 요청별 사용 이력을 함께 삭제합니다.

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

V1~V12 적용 DB에 현재 Backend를 시작해 V13이 추가 적용되는 경로와, 빈 PostgreSQL에서 V1→V13이 순서대로 적용되는 경로를 각각 확인합니다.

- Flyway 로그에 V1부터 V13까지 적용 성공이 출력됩니다.
- `flyway_schema_history`에 version 1부터 13까지 성공으로 기록됩니다.
- `vector` extension이 활성화됩니다.
- `episode_chunks.embedding`이 `vector(1536)`으로 생성됩니다.
- cosine HNSW 인덱스가 생성됩니다.
- `character_setting_schemas`에 전역 seed 31개(`SYSTEM_SEED=16`, `DEV_SEED=15`)가 생성됩니다.
- `stats.attribute`가 `stats.*`, `STAT`, `NUMBER`, `REPLACE` 정책으로 생성됩니다.
- `setting_candidates.source_content_s3_key`가 nullable `VARCHAR(512)`로 생성됩니다.
- `ai_token_accounts`, `ai_token_grants`, `ai_token_usages`가 생성되고 요청·회원 조회 인덱스와 삭제 정책이 적용됩니다.
- `character_facts.setting_candidate_id`와 FK·조회 인덱스가 생성됩니다.
- `works.genre`가 enum 상수명으로 저장되고 `NOT NULL`·`chk_works_genre` 제약을 가집니다.
- `works.description`이 기존 값의 앞 50자로 정규화되고 `VARCHAR(50)` 타입을 가집니다.
- `upload_files.archived_at`과 `idx_upload_files_active_setting_books` 인덱스가 생성됩니다.
- `episodes.content_updated_at`이 기존 데이터까지 채워지고 `NOT NULL` 제약을 가집니다.
- `analysis_job_episode_targets`와 회차 역방향 조회 인덱스가 생성되고 기존 작업 대상이 backfill됩니다.
- `upload_files.content_storage_url`이 nullable `VARCHAR(512)`로 생성됩니다.
- `idx_characters_work_status_created_id` 복합 인덱스가 생성됩니다.
- `idx_analysis_jobs_work_batch_created`, `idx_setting_candidates_job_review` 인덱스가 생성됩니다.
- Hibernate schema validation을 통과하고 Backend가 정상 시작됩니다.
- Backend를 재시작해도 V1부터 V13까지 중복 적용되지 않습니다.

## 최초 운영 전환

Flyway 도입 전에 JPA가 만든 운영 테스트 DB에는 `flyway_schema_history`가 없습니다. 이 상태에서 V1을 자동 baseline 처리하면 V1이 건너뛰어져 `episode_chunks` 등 신규 schema가 생성되지 않으므로 `baseline-on-migrate`를 사용하지 않습니다.

현재 데이터가 테스트용이라는 팀 합의를 전제로 다음 순서로 한 번 초기화합니다.

1. 필요한 데이터가 없는지 확인하고 필요하면 `pg_dump`로 백업합니다.
2. Backend와 AI Worker를 중지합니다.
3. PostgreSQL 데이터 volume만 제거하고 빈 PostgreSQL 16 DB를 시작합니다.
4. Backend를 시작해 Flyway V1~V13과 Hibernate validation 성공을 확인합니다.
5. DB schema와 Swagger 기본 API를 확인한 뒤 AI Worker를 시작합니다.

실제 사용자 데이터가 생긴 뒤에는 이 초기화 절차를 사용하지 않습니다. 기존 데이터를 보존하는 V2 이상의 `ALTER` migration과 사전 백업·롤백 계획을 별도로 작성합니다.
