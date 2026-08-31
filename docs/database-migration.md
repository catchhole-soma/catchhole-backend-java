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
├── V13__add_ai_token_quota.sql
├── V14__cascade_ai_token_history_cleanup.sql
├── V15__add_setting_candidate_kind.sql
└── V16__add_world_settings.sql
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

## V23 기준

V23은 캐릭터 DB에서 최근에 설정이나 기본 정보가 바뀐 캐릭터를 먼저 조회하도록 목록 정렬 인덱스를 교체합니다.

- V9의 `idx_characters_work_status_created_id`를 제거합니다.
- `characters(work_id, status, updated_at DESC, id DESC)` 순서의 `idx_characters_work_status_updated_id`를 생성합니다.
- 작품과 상태를 제한한 뒤 `updatedAt DESC, id DESC` 정렬을 지원하며, 같은 수정 시각에도 페이지 순서가 흔들리지 않습니다.

## V24 기준

V24는 캐릭터 2차 비교가 현재 `STATUS` slot의 종료를 제안할 때 `setting_candidates.suggested_operation`에 `REMOVE`를 저장할 수 있도록 check constraint를 확장합니다.

## V25 기준

V25는 분석·후보 비교 실패를 예외 문자열 파싱 없이 분류하고 토큰 중단 세계관 후보를 선택적으로 재개할 수 있게 합니다.

- `analysis_jobs.failure_code VARCHAR(60) NULL`을 추가합니다.
- `world_setting_candidates.comparison_failure_code VARCHAR(60) NULL`을 추가합니다.
- `setting_candidates.comparison_failure_code VARCHAR(60) NULL`을 추가합니다.
- `PENDING_REVIEW + FAILED + AI_TOKEN_QUOTA_EXHAUSTED` 후보의 배치 복구 조회를 위한 PostgreSQL partial index를 추가합니다.
- 기존 행은 원인을 안전하게 추정할 수 없으므로 세 코드 모두 `NULL`로 유지하고 backfill하지 않습니다.

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

## V14 기준

V14는 이미 적용된 V13을 수정하지 않고 AI 토큰 테이블 외래 키의 삭제 전파 정책을 추가합니다.

- 회원 삭제 시 해당 회원의 계정·지급·사용 이력을 함께 삭제합니다.
- 작품 또는 분석 작업 삭제 시 연결된 요청별 사용 이력을 함께 삭제합니다.

## V15 기준

V15는 설정값 없이 이름의 존재만 검토하는 캐릭터 발견 후보를 기존 `setting_candidates` 흐름에 추가합니다.

- `candidate_kind VARCHAR(30) NOT NULL DEFAULT 'SETTING'`을 추가해 기존 행과 구버전 Worker insert를 `SETTING`으로 유지합니다.
- `attribute_name`, `value_type`의 `NOT NULL`을 제거합니다.
- check constraint는 `SETTING`에 `attribute_name`과 `value_type`이 존재하도록 하고, `CHARACTER_DISCOVERY`에는 `attribute_name`, `attribute_value`, `value_type`, `value_json`이 모두 `NULL`이도록 강제합니다.
- 발견 후보는 이름·원문 표현·근거·신뢰도·검토 상태를 기존 컬럼에 보존하며 별도 테이블을 만들지 않습니다.

## V16 기준

V16은 작품별 현재 세계관 확정본과 회차 분석 후보를 캐릭터 설정 모델과 분리해 저장합니다.

- `world_settings` 한 행은 `work_id + category + normalized_subject_name` 대상 하나이며, 문자열 설정명·값은 object 형태 `properties_json`에 누적합니다.
- 동일 작품·분류·정규화 대상명 unique 제약과 JSON object check를 DB 최종 방어선으로 사용합니다.
- `version`은 직접 수정과 후보 확정의 동시성 기준이며 실제 속성 또는 대상 정보 변경마다 증가합니다.
- `world_setting_candidates`는 회차·분석 작업을 필수 FK로 연결하고 1차 추출, 2차 비교 제안, 사용자 최종 결정 컬럼을 한 행에 보존합니다.
- `world_settings.work_id`와 `world_setting_candidates.work_id`는 작품 hard delete 시 작품 소유 세계관 데이터가 남아 삭제를 막지 않도록 `ON DELETE CASCADE`를 사용합니다. 개별 세계관 대상 삭제 API를 제공한다는 뜻은 아닙니다.
- 분류, 비교 작업, 비교 상태, 검토 상태는 Java enum과 같은 허용값 check를 적용합니다.
- 원문 근거는 JSON array, 신뢰도는 `0..1`, 비교·적용 버전은 0 이상으로 제한합니다.
- 설정집 출처 FK와 `world_setting_facts`, 삭제·보관·복원·전체 변경 이력 테이블은 만들지 않습니다.

## V19 기준

V19는 세계관 후보에 선택적 1단계 범위 경로를 추가합니다.

- `world_setting_candidates`에 `scope_name`, `proposed_scope_name`, `final_scope_name` nullable 문자열 컬럼을 추가합니다. `NULL`은 루트 property입니다.
- 기존 후보와 확정 JSONB를 임의 범위로 이전하지 않습니다. 기존 문자열 leaf는 루트 property로 계속 해석합니다.
- `world_settings.properties_json`은 루트 문자열 leaf와 1단계 범위 object를 함께 허용하되 도메인 계층에서 2단계 중첩, 같은 전체 경로 중복, 루트 leaf/scope object 충돌을 검증합니다.
- 이 마이그레이션은 세계관 테이블만 변경하며 캐릭터 설정 테이블의 key 정규화·중복 계약을 변경하지 않습니다.

## V20 기준

V20은 캐릭터 현재값 판단을 `CharacterFact.is_current`에서 `WorkCharacter` snapshot과 정규화 provenance로 전환합니다.

- `characters.snapshot_version`을 추가합니다. snapshot 값 또는 source link가 바뀐 트랜잭션에서 정확히 한 번 증가합니다.
- `character_snapshot_sources`를 만들고 기존 `is_current=true` 중 `(character, factType, factKey)`별 적용 회차·생성 시각·ID 기준 최신 한 건만 source로 backfill합니다. 중복 current 이상 데이터를 MERGE로 추정하지 않습니다.
- provenance backfill 뒤 기존 current 조회 인덱스와 `character_facts.is_current`를 제거합니다. 따라서 V20 이후 구버전 애플리케이션 이미지로 단순 rollback할 수 없고 forward repair migration이 필요합니다.
- `analysis_jobs.setting_candidate_id`와 active hidden Job unique index를 추가해 `CHARACTER_FACT_COMPARISON` Job 한 건이 후보 한 건만 재비교하도록 합니다.
- `setting_candidates`에 비교 상태·operation·시간 범위·target slot·제안 JSON·제거 slot·문맥 hash·감사/오류 컬럼을 추가합니다.
- 기존 `PENDING_REVIEW + MATCHED` 행은 이미 끝난 원본 Job에 새 Worker stage가 없으므로 `PENDING`으로 backfill하지 않고 `NOT_REQUIRED`로 둡니다. 사용자가 비교를 시작하거나 confirm을 시도하면 hidden Job으로 전환합니다. 미매칭 설정 후보만 `WAITING_FOR_CHARACTER_MATCH`, 그 밖의 기존 행은 `NOT_REQUIRED`입니다. 신규 AI Worker가 저장하는 매칭 설정 후보부터 `PENDING`을 명시합니다.

V20은 아직 공유 환경에 적용되지 않은 이 브랜치 migration을 배포 안전성 검토로 정정한 것입니다. 이전 SQL checksum으로 V20 또는 V22를 이미 적용한 개인 테스트 DB는 재생성하는 것을 권장합니다. 데이터를 보존해야 하는 DB는 최종 schema·후보 상태·인덱스를 현재 migration과 대조하고, 누락된 변경을 보정 SQL 또는 새 forward migration으로 실제 적용한 뒤에만 checksum을 repair합니다. `flyway repair`는 이미 실행된 DDL·데이터를 되돌려 적용하지 않으므로 공유 DB에서 검증 없이 실행하면 안 됩니다. 공유 환경에 적용된 뒤에는 기존 migration을 다시 수정하지 않습니다.

## V21 기준

V21은 캐릭터 비교 proposal의 최종 사용자 표시 문자열 `setting_candidates.proposed_fact_value`를 추가합니다. 구조화 `proposed_value_json`만으로는 MERGE한 자연어 표시값을 복원할 수 없으므로 둘을 함께 저장합니다.

## V22 기준

V22는 provenance source가 같은 캐릭터뿐 아니라 같은 canonical slot의 Fact임을 DB에서도 보장합니다.

- `character_facts(character_id, fact_type, fact_key, id)` unique key를 추가합니다.
- `character_snapshot_sources(character_id, fact_type, fact_key, source_fact_id)` 복합 FK로 교체합니다.

## V27 기준

V27은 회원가입 당시 적용된 이용약관 동의와 개인정보처리방침 확인을 증명하는 `member_legal_records`를 추가합니다.

- 프론트는 한 체크박스로 두 문서를 함께 확인하지만 API는 두 의미를 각각 `true`로 검증합니다.
- 서버가 현재 서비스 화면 문서 버전 `2026-08-23`을 선택하고 이용약관은 `AGREED`, 개인정보처리방침은 `ACKNOWLEDGED`로 구분해 가입 트랜잭션 안에서 두 행을 저장합니다. 화면 문구 변경 시 Front 표시 버전과 Backend 버전을 함께 올립니다.
- 같은 회원·문서·버전의 중복 이력은 unique 제약으로 막고 회원 삭제 시 이력도 함께 삭제합니다.
- AI 원고 처리는 계약 이행에 필요한 처리 안내로 운영하므로 별도 동의 행을 만들지 않습니다.

V27의 문서 버전 문자열 저장은 당시 간략 문구를 기록한 이력이며, V31부터는 실제 원문 FK가 런타임 기준입니다.

## V28 기준

V28은 회차 수정·파일 교체에서 새 원문 참조를 커밋하기 전에 이전 S3 원문이 먼저 삭제되는 경합을 막기 위해 `episode_source_purge_requests`를 추가합니다.

- 회차별 활성 정리 요청은 한 행만 허용합니다.
- 이전 회차 번호·원문 key·업로드 원본 URL과 새로 유지할 원문 key를 스냅샷으로 저장합니다.
- `REQUESTED`, `PROCESSING`, 시도 횟수와 정규화된 실패 코드를 저장해 서버 재시작과 저장소·DB 실패 뒤에도 재시도합니다.
- 정리가 끝나면 요청 행을 즉시 삭제하고, 작품 영구 삭제 시 남은 요청도 작품 DB 데이터와 함께 삭제합니다.

## V29 기준

V29는 같은 정리 요청을 회차 삭제에도 사용하도록 `episode_source_purge_requests.retained_content_key`를 nullable로 변경합니다.

- 수정·파일 교체 요청은 새 원문 key를 계속 저장해 이전 prefix 정리에서 제외합니다.
- 삭제 요청은 유지할 원문이 없으므로 `NULL`을 저장하고 tombstone 커밋 후 모든 이전 원문을 재시도 가능하게 파기합니다.

## V30 기준

V30은 회원 즉시 탈퇴를 내구성 있게 조정하는 `member_withdrawal_requests`를 추가합니다.

- 회원은 `ACTIVE → PURGING` 뒤 모든 작품 파기가 끝나면 행 자체를 삭제하므로 별도 회원 상태 check 변경은 없습니다.
- `member_withdrawal_requests.member_id`에는 FK를 두지 않아 회원 삭제 후에도 이메일·휴대폰 번호 없는 최소 완료 감사를 기본 1년 유지합니다.
- 같은 이유로 기존 `work_purge_requests.member_id`의 회원 FK를 제거해 작품 파기 감사 row가 회원과 함께 cascade 삭제되지 않게 합니다.
- 다른 작품 데이터의 선택적 `world_setting_candidates.reviewed_by`는 회원 삭제 시 `NULL`이 되도록 FK를 `ON DELETE SET NULL`로 바꿉니다.
- 회원이 소유한 `works`, `upload_batches`의 회원 FK는 그대로 유지해 기존 WorkPurge가 끝나기 전에 회원 행을 삭제하지 못하게 합니다.

## V31 기준

V31은 장문 이용약관·개인정보처리방침 원문과 게시 수명주기를 `legal_documents`로 도입하고 회원가입 이력을 실제 문서 FK에 연결합니다.

- `document_type + locale + document_version`을 unique로 두고 `DRAFT`, `PUBLISHED`, `RETIRED` 수명주기와 시행·게시·폐기 시각 제약을 적용합니다.
- 부분 unique index로 문서 종류+locale별 현재 `PUBLISHED`를 한 건만 허용하고 공개 조회용 인덱스를 추가합니다.
- `content_markdown` 원문과 UTF-8 SHA-256 `content_hash`를 함께 저장합니다. 게시된 원문을 수정하지 않고 새 버전을 게시합니다.
- 기존 `2026-08-23` 간략 문서는 `RETIRED`, `2026-08-24` 장문 원문은 바로 `PUBLISHED`로 seed합니다.
- `member_legal_records.legal_document_id`를 기존 종류·버전과 일치하는 문서로 backfill하고 `NOT NULL` FK·회원+문서 unique로 전환합니다. 감사용 종류·버전·행위 snapshot은 유지합니다.
- `members.age_requirement_confirmed_at`을 nullable로 추가해 기존 회원은 유지하고, 신규 가입은 만 14세 이상 필수 확인과 같은 서버 시각을 저장합니다.
- GA4·Meta Pixel의 실제 코드는 이 migration에서 설치하지 않으며 관련 자동 수집 고지는 개인정보처리방침 원문에 포함합니다.

## V35 기준

V35는 캐릭터 비교 정책 변경 전에 남아 있던 `PENDING_REVIEW + COMPLETED + EXCLUDE` 후보를 사용자 확인 없이 완료된 자동 무시 상태로 이관합니다.

- 대상 후보 행만 `DISMISSED + NOT_REQUIRED`로 바꾸고 `SettingCandidate.dismiss()`와 같은 비교 proposal 필드를 비웁니다.
- `setting_candidates` 원본 행과 추출 근거는 유지합니다.
- `characters`, `character_facts`, `character_snapshot_sources`는 수정하지 않으므로 기존 현재 설정·provenance·이력은 그대로 유지합니다.

## V36 기준

V36은 범위 없는 세계관 후보와 기존 scoped 동명 속성 사이의 사용자 범위 확인 상태를 저장합니다.

- `world_setting_candidates`에 nullable `matched_scope_name`, `matched_property_name`, `comparison_review_reason`을 추가합니다.
- `suggested_operation` check constraint를 교체해 `REVIEW_REQUIRED`를 허용합니다.
- `REVIEW_REQUIRED`는 `SCOPE_UNRESOLVED` 사유와 함께만 저장하고, 그 밖의 operation은 사유가 `NULL`일 때만 허용합니다. 두 방향 모두 명시적인 `IS NOT NULL` 조건을 사용해 PostgreSQL `CHECK`의 `UNKNOWN` 통과를 막습니다.
- 기존 후보는 새 컬럼을 `NULL`로 유지하며 별도 상태 추론이나 backfill을 하지 않습니다.

## V37 기준

V37은 세계관 comparison-complete 계약 오류의 Spring 원인을 사용자용 상위 실패 분류와 분리해 보존합니다.

- `world_setting_candidates.comparison_source_error_code VARCHAR(100) NULL`을 추가합니다.
- `world_setting_candidates.comparison_source_reason_code VARCHAR(100) NULL`을 추가합니다.
- source error code는 대문자·숫자·밑줄 형식만, source reason은 `WorldSettingComparisonValidationReason` enum 값만 허용합니다.
- 기존 `comparison_failure_code`와 사용자 공개 응답 계약은 바꾸지 않으며 기존 행은 두 컬럼 모두 `NULL`로 유지합니다.

## 논리 참조와 FK 기준

ID 컬럼이 다른 테이블을 논리적으로 가리키더라도 삭제·재처리 정책이 정해지지 않았다면 FK를 먼저 강제하지 않습니다. V1의 선택은 다음과 같습니다.

| 컬럼 | 참조 대상 | V1 정책 | 이유 및 후속 논의 |
| --- | --- | --- | --- |
| `episodes.source_file_id` | `upload_files.id` | nullable FK, 기본 `NO ACTION` | 업로드 파일을 먼저 저장한 뒤 회차를 생성하고 현재 업로드 파일 삭제 API가 없으므로 원본 추적 무결성을 강제합니다. 삭제 기능을 추가하면 연결된 회차 처리 정책을 함께 정합니다. |
| `characters.first_appearance_episode_id` | `episodes.id` | FK 보류 | 회차 원문 파기 후에도 `ARCHIVED` Episode tombstone은 유지되며, 향후 Episode 행 물리 삭제 시 최초 등장 회차를 재계산할지 `NULL`로 둘지 먼저 결정해야 합니다. |
| `setting_candidates.source_chunk_id` | `episode_chunks.id` | FK 보류 | 재청킹이 기존 청크를 삭제하고 새 UUID로 교체하므로 일반 FK는 재청킹을 막고, cascade 또는 set null은 근거를 손실할 수 있습니다. |
| `character_facts.source_chunk_id` | `episode_chunks.id` | FK 보류 | 확정 설정의 원문 근거이므로 청크 ID 안정화 또는 청크 이력 보존 정책을 정한 뒤 FK를 검토합니다. |

FK를 보류한 컬럼도 임의 UUID 용도가 아니라 위 참조 대상을 저장하는 논리 연결입니다. 후속 정책이 정해지면 새 migration에서 제약조건과 삭제 동작을 추가합니다.

## 로컬 검증

기존 적용 DB에 현재 Backend를 시작해 미적용 migration이 V37까지 추가 적용되는 경로와, 빈 PostgreSQL에서 V1→V37이 순서대로 적용되는 경로를 각각 확인합니다.

- Flyway 로그에 V1부터 V37까지 적용 성공이 출력됩니다.
- `flyway_schema_history`에 version 1부터 37까지 성공으로 기록됩니다.
- `vector` extension이 활성화됩니다.
- `episode_chunks.embedding`이 `vector(1536)`으로 생성됩니다.
- cosine HNSW 인덱스가 생성됩니다.
- `character_setting_schemas`에 전역 seed 31개(`SYSTEM_SEED=16`, `DEV_SEED=15`)가 생성됩니다.
- `stats.attribute`가 `stats.*`, `STAT`, `NUMBER`, `REPLACE` 정책으로 생성됩니다.
- `setting_candidates.source_content_s3_key`가 nullable `VARCHAR(512)`로 생성됩니다.
- V13에서 `ai_token_accounts`, `ai_token_grants`, `ai_token_usages`와 요청·회원 조회 인덱스가 생성됩니다.
- V14에서 토큰 계정·지급·사용 이력의 회원·작품·분석 작업 삭제 전파 정책이 적용됩니다.
- V15에서 `setting_candidates.candidate_kind`와 후보 종류별 nullable payload 제약이 적용됩니다.
- V16에서 `world_settings`, `world_setting_candidates`와 unique·enum·JSON·범위 제약 및 조회 인덱스가 생성됩니다.
- V20에서 `character_snapshot_sources`, `snapshot_version`, 캐릭터 비교 proposal·hidden Job 컬럼과 제약이 생성되고 `character_facts.is_current`가 제거됩니다.
- 기존 slot별 source backfill은 deterministic 최신 한 건이고, 기존 매칭 후보는 `NOT_REQUIRED`, 기존 미매칭 설정 후보는 `WAITING_FOR_CHARACTER_MATCH`로 이관됩니다.
- V21에서 `setting_candidates.proposed_fact_value`, V22에서 same-character/same-slot provenance 복합 FK가 생성됩니다.
- V24에서 `setting_candidates.suggested_operation` check constraint가 `REMOVE`를 허용합니다.
- V25에서 분석 작업과 캐릭터·세계관 후보 비교의 typed 실패 코드가 생성됩니다.
- V26에서 `works.lifecycle_status`, `work_purge_requests`와 처리·만료 조회 인덱스가 생성됩니다. 작품 삭제 뒤에도 파기 결과를 조회해야 하므로 삭제 요청의 작품 ID에는 의도적으로 FK를 두지 않습니다.
- V27에서 `member_legal_records`와 회원·기록 시각 조회 인덱스가 생성되고, 가입 시 약관 동의와 방침 확인이 서로 다른 행위 유형으로 기록됩니다.
- V28에서 `episode_source_purge_requests`와 상태·요청 시각 조회 인덱스가 생성되고, 회차별 활성 요청 unique와 Episode 삭제 cascade가 적용됩니다.
- V29에서 삭제 요청은 `retained_content_key=NULL`을 저장할 수 있고 교체 요청은 유지할 새 원문 key를 계속 저장합니다.
- V30에서 `member_withdrawal_requests`와 처리·만료 조회 인덱스가 생성되고, 작품 파기 감사의 회원 FK 제거와 검수자 회원 FK의 `ON DELETE SET NULL`이 적용됩니다.
- V31에서 `legal_documents` 4건(기존 2건 `RETIRED`, 현재 2건 `PUBLISHED`)과 현재 게시본 partial unique가 생성되고, 기존 `member_legal_records`가 실제 문서 FK로 backfill됩니다.
- V31의 현재 두 문서 `content_hash`가 Front `docs/legal/` 원문의 UTF-8 SHA-256과 일치하며 `members.age_requirement_confirmed_at`이 Entity와 일치합니다.
- V35에서 기존 `PENDING_REVIEW + COMPLETED + EXCLUDE` 캐릭터 후보가 `DISMISSED + NOT_REQUIRED`로 이관되고 캐릭터 snapshot·Fact·provenance 행 수와 값은 바뀌지 않습니다.
- V36에서 세계관 후보의 matched scope/property와 구조화된 review reason 컬럼, `REVIEW_REQUIRED` operation, `REVIEW_REQUIRED + SCOPE_UNRESOLVED`의 NULL-safe 조합 제약이 생성됩니다.
- V37에서 세계관 비교 실패의 Spring source error/reason 컬럼과 안전한 값 제약이 생성됩니다.
- `character_facts.setting_candidate_id`와 FK·조회 인덱스가 생성됩니다.
- `works.genre`가 enum 상수명으로 저장되고 `NOT NULL`·`chk_works_genre` 제약을 가집니다.
- `works.description`이 기존 값의 앞 50자로 정규화되고 `VARCHAR(50)` 타입을 가집니다.
- `upload_files.archived_at`과 `idx_upload_files_active_setting_books` 인덱스가 생성됩니다.
- `episodes.content_updated_at`이 기존 데이터까지 채워지고 `NOT NULL` 제약을 가집니다.
- `analysis_job_episode_targets`와 회차 역방향 조회 인덱스가 생성되고 기존 작업 대상이 backfill됩니다.
- `upload_files.content_storage_url`이 nullable `VARCHAR(512)`로 생성됩니다.
- V9에서 `idx_characters_work_status_created_id`가 생성되고 V23에서 최근 수정순 조회용
  `idx_characters_work_status_updated_id`로 교체됩니다.
- `idx_analysis_jobs_work_batch_created`, `idx_setting_candidates_job_review` 인덱스가 생성됩니다.
- Hibernate schema validation을 통과하고 Backend가 정상 시작됩니다.
- Backend를 재시작해도 V1부터 V37까지 중복 적용되지 않습니다.

## 최초 운영 전환

Flyway 도입 전에 JPA가 만든 운영 테스트 DB에는 `flyway_schema_history`가 없습니다. 이 상태에서 V1을 자동 baseline 처리하면 V1이 건너뛰어져 `episode_chunks` 등 신규 schema가 생성되지 않으므로 `baseline-on-migrate`를 사용하지 않습니다.

현재 데이터가 테스트용이라는 팀 합의를 전제로 다음 순서로 한 번 초기화합니다.

1. 필요한 데이터가 없는지 확인하고 필요하면 `pg_dump`로 백업합니다.
2. Backend와 AI Worker를 중지합니다.
3. PostgreSQL 데이터 volume만 제거하고 빈 PostgreSQL 16 DB를 시작합니다.
4. Backend를 시작해 Flyway V1~V37과 Hibernate validation 성공을 확인합니다.
5. DB schema와 Swagger 기본 API를 확인한 뒤 AI Worker를 시작합니다.

실제 사용자 데이터가 생긴 뒤에는 이 초기화 절차를 사용하지 않습니다. 기존 데이터를 보존하는 V2 이상의 `ALTER` migration과 사전 백업·롤백 계획을 별도로 작성합니다.
