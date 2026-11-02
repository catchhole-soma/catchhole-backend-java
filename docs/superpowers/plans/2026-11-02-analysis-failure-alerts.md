# GH357 분석 실패 알림 개선

Goal: 관계도·세계관의 안전한 원인을 보존하고 동일 실행 시도의 알림을 읽기 쉽게 묶는다.
Architecture: 기존 v1 diagnostics와 transactional outbox를 유지한다. DB 전용 UUID로 실행 시도를 구분하고, 발송 묶음 row를 잠가 후보/작업 snapshot을 함께 선점한다. 묶음은 최초 선점 때 봉인해 재전송 내용이 바뀌지 않는다. 5초 수집 대기(최대 30초), 메시지 길이 상한을 위한 최대 4개 snapshot 묶음을 사용한다. 늦게 도착한 실패는 같은 실행 시도 ID의 후속 묶음이다.
Tech Stack: Java 21/Spring/JDBC/Flyway/PostgreSQL, Python/pytest.
Spec: 사용자 요청 및 Java issue #357.

## Global Constraints
- 원문·provider 응답·임의 예외 문자열·lease token을 진단에 넣지 않는다.
- 실패 판정, 재시도, 과금, 모델/프롬프트를 바꾸지 않는다.
- 기존 migration 및 과거 outbox snapshot은 변경하지 않는다.
- 이미 main에 반영된 관계도 진단 코드를 재구현하지 않는다.
- 새 묶음은 명시적인 실행 링크와 시도가 있을 때만 합친다. legacy 기록은 별개로 전달한다.

## Task 1: 원인 진단·표시
- 세계관 주어 해석 실패/입력량 제한에 고정 단계·reason을 저장한다.
- 관계도 세부 규칙은 각각의 한국어 설명을 표시한다. 재개된 FAILED 관계도는 응답에 원인 코드가 없으므로 이전 실패의 세부 원인 미저장을 명시한다.
- 선택 정보 누락은 숨기고 quota는 충전 안내를 우선한다.
- Java entity/mapper, Python pipeline/worker 회귀를 먼저 실패시키고 구현한다.

## Task 2: 묶음 발송
- V107에 신규 진단 허용 목록, DB 관리 실행 시도 UUID, 발송 묶음과 trigger를 추가한다.
- claim/ack/retry는 묶음 전체를 짧은 DB 트랜잭션으로 처리하고 HTTP는 바깥에서 실행한다.
- 실제 PostgreSQL에서 여러 트랜잭션의 후보+작업, 재시도 구분, 동시 선점, 봉인 뒤 늦은 실패, 롤백, 재전송을 검사한다.

## Task 3: 검증·문서
- 관련 테스트, 전체 Java/AI 테스트와 빌드, migration/JPA validate를 수행한다.
- 문서·AGENTS에 새 계약과 배포 순서/과거 기록 한계를 기록한다.
- 최종 독립 코드 리뷰와 필요한 수정 후 결과를 제공한다. 배포하지 않는다.

## Review Focus
트랜잭션 스냅샷/동시 선점에서 누락·중복 ack, 실패 시도 사이 잘못된 병합, 재전송 내용 변경, 개인정보 노출, 2000자 제한, 실제 의미를 초과한 원인 추정.

## Progress
- 준비: issue #357. native worktree API는 nested repo 경로를 거절하여 Git worktree fallback 사용. main Java 8f31b984 / AI 1d6ea3c에서 시작.

- Task 1 완료: Java 원인 표시/세계관 entity 6개 신규 회귀 RED→GREEN. Python FAILED 재개 회귀 RED→GREEN, 관련 95개 통과.
- Task 2 완료: PG 실행 시도·delivery 부재 RED, 묶음 선점 부재 RED, dispatcher 단건 선점 RED 후 구현. 관련 Java 집중 테스트 통과(실제 pgvector PostgreSQL, 전체 Flyway 및 JPA validate 포함).
- 결정: 기존 main의 관계도 진단 보존을 기반으로 한국어 설명을 개선한다. 저장 FAILED 문맥 응답에는 원인 코드가 없으므로 과거 원인을 복원하는 대신 원인 미저장 재개라고 표시한다.
- Task 3 진행: Python 전체 5100 passed / 143 skipped / 기존 Starlette warning 1. Java 전체 및 GH304 HTTP 연결 검사 중.
- 독립 리뷰: 실행 시도 UUID를 outbox unique 그룹 키에 포함, 관계 설명의 대안 검증 조건 보존, 묶음의 원인 근거/요청 ID 보존이 필요하다는 세 항목을 확인했다. 발생 시각 표시도 함께 보완한다. 회귀를 먼저 추가하고 한 번의 수정·재검증으로 처리한다.
- 운영 실제 Discord 수신/마이그레이션 시간은 배포 범위 밖이라 측정하지 않는다. 코드 rollback의 단건 발송은 기존 outbox 전달을 유지하기 위한 문서화된 호환 한계다.

- 최종 리뷰 수정: 관계 설명 5개·묶음 원인 링크/시간·동일 트랜잭션 재시도·실제 HTTP 묶음 전달의 8개 회귀가 모두 RED인 것을 확인한 후 수정했다. 집중 141 tests / 0 failures / 0 skipped GREEN. 기존 전체 Java 실행의 HTTP 6실패는 최초 단건 2회 전송 기대 실패와 그 fixture 후속 claim 간섭으로 확인했고, 갱신된 HTTP 8개를 포함해 전부 통과했다. 최종 전체 재실행 중.

- 완료 검증: 최종 `GH304_AI_CHECKOUT=... GH304_PYTHON=... ./gradlew test bootJar` 성공(5분 35초). XML 기준 Java 4,321 passed / 317 skipped / 실패·오류 0; 실제 알림 PostgreSQL 51개와 HTTP 연결 8개는 모두 실행되어 통과. Python 전체 5,100 passed / 143 skipped / 실패 0(33.84초, 기존 Starlette deprecation warning 1). `git diff --check` 양 저장소 통과. 운영 배포·실제 Discord 전송·기존 메시지 수정은 수행하지 않았다.

## 후속 요구: 실제 값과 처리 결과 (2026-11-02)
- 실패 지점에서 안전한 고정 field/rule 및 expected/actual을 수집한다. 수치·local 참조·enum·boolean을 먼저 지원하며 원문/응답 구절은 사용자 선호 확인 중이다. 재시도/판정을 변경하지 않는다.
- 세계관 subject 실패 보고에도 optional failureDiagnostics를 운반해 세부 검사가 사라지지 않게 한다.
- 관계도 입력량(토큰·청크·관측 수), 관계 검증의 실제 참조/상태, 세계관 연결의 선택 참조/coverage를 보존한다.
- V108은 안전한 checks와 후보 처리 상태/ID를 실패 시점에 snapshot한다. JOB_FAILED와 CANDIDATE_FAILED_REVIEW_PENDING을 구분하고 후보 사건만으로 Job의 최종 성공/실패를 단정하지 않는다. 정상 REVIEW_REQUIRED를 장애 알림으로 추가하지 않는다.
- 상세값이 묶음 길이에 밀리지 않도록 Discord 한 메시지 내 최대 4개 embed로 사건별 상세를 표시한다. 동일 묶음의 선점/ack/retry 계약은 유지한다.
- 검증: Python 실제 실패 전파, Java 정제/표시, 실제 PostgreSQL snapshot, 가짜 Discord HTTP 및 양쪽 전체 테스트.

- 후속 요구 구현 완료: 검사별 expected/actual(최대3개), 세계관 subject 최종 실패 진단 운반, V108 후보 상태/ID snapshot, 최대4개 Discord embed의 처리 결과/원인별 수/실제 검사 표시. 정상 검토와 실패 분류·재시도·차감은 유지한다.
- 최종 검증: Java 전체 test bootJar 4,330 passed / 317 skipped / failures0 (5분29초). 이후 표시·선점 경계 수정은 최종 집중 206 passed / 0 skipped와 bootJar로 검증했다(실제 PG55 + Python HTTP8 포함). Python 최종 전체 5,106 passed / 143 skipped / 실패0 (29.44초, 기존 Starlette 경고1).
- 독립 리뷰 중요 지적 모두 해결: 구 알림 기록 Job/회차 ID, 원인별 후보 수. duplicate UNIQUE/오류 참조 우선 표시도 반영했다. 선택 뒤 유효 lease가 생긴 경계에서 덮어쓰는 회귀를 RED→GREEN으로 확인하고 fresh 묶음/행 eligibility 조건을 보완했다. 자연 발생 동시 스케줄 자체의 재현으로 주장하지 않는다.
- production mapper 합성 미리보기는 workspace output/discord-failure-details-preview-20261102.txt. 기대 S1,S2/실제 S9, 한도64000/관측72001은 형식 검증용 값이며 운영 사건의 값이 아니다. 원문/응답 구절은 포함하지 않는다. 운영 배포·커밋·외부 Discord 발송은 수행하지 않았다.

## 후속 사용자 승인과 문구 수정
- 문제 원문/AI 응답을 짧게 포함하도록 명시 승인받았다. V109는 최대3개/120 code point의 고정 발췌와 대상 이름60 code point를 보존한다.
- 사용자가 ‘대상 연결/S1/S2’만으로 의미를 이해하기 어렵다고 지적했고, 실제 대상 이름·행동·결과를 앞에 표시하도록 수정했다. 이유는 ‘AI가 선택지에 없는 대상을 골랐습니다.’로 줄인다.
- ‘전체 분석 중단 여부 확인 불가’는 이 오류의 영향과 작업 최종 상태를 혼동하는 문구였다. 서버의 명시적 자동 후보 보류 경로를 snapshot해 ‘이 설정만 자동 반영 중단 · 나머지 분석 계속’으로 표시한다. PENDING_REVIEW만으로 추정하지 않는다.
- 후보 근거/기존 값/응답 문제 필드만 보존하며 원문 파기에 따라 작품 단위 발췌 사본을 제거한다. 원문 재조회·재분석·운영 발송은 수행하지 않는다.
- RED→GREEN: bounded excerpt 정제·긴 MERGE 차이·세계관 후보 근거/응답·실제 대상 이름 표시·PG 발췌/파기·계속 진행 경로 snapshot. 리뷰의 늦은 내부 참조가 발췌에서 빠지는 회귀도 재현 후 해당 참조 주변으로 수정했다.
- 전체 검증 중 파기 repository의 PostgreSQL 전용 정리가 migration 테이블 없는 H2 테스트 18개를 깨뜨리는 회귀를 확인했다. 해당 Java 변경을 제거하고 V109 파기 요청 DELETE 트리거로 이동했으며 관련 H2·PostgreSQL 집중 테스트를 통과했다.
- 파기 리뷰의 PROCESSING 상태 조건과 비교 묶음 사본 누락을 두 상태(PROCESSING/REQUESTED)의 실제 PostgreSQL 회귀로 RED 재현했다. 조건을 제거하고 Job·캐릭터/세계관 후보·캐릭터/세계관 비교 묶음·outbox 6개 저장소로 정리를 확장한 뒤 모두 GREEN을 확인했다.
- Python의 기존 ‘발췌를 전혀 허용하지 않음’ 기대를 명시 승인된 문제 필드만 허용하는 계약으로 갱신했다. 최종 전체 pytest 5,110 passed / 143 skipped / 기존 Starlette warning 1 (31.31초).
- 최종 Java 전체 `test bootJar`: 4,337 통과 / 317 건너뜀 / 실패·오류 0 (5분 33초). 실제 PostgreSQL 알림 58개 및 Python→Spring→PostgreSQL→가짜 Discord HTTP 8개 모두 실행·통과.
- 최종 Python 전체: 5,110 통과 / 143 건너뜀 / 실패 0 (31.31초, 기존 Starlette 경고 1개).
- 독립 리뷰의 늦은 참조 발췌, 파기 요청 상태 경합, 두 비교 묶음의 발췌 사본 누락을 RED→GREEN으로 수정. 최종 중요/치명 지적 없음. 양 저장소 `git diff --check` 통과.
- 실제 알림 생성 코드의 합성 미리보기: `output/discord-failure-excerpts-preview-20261102.txt`. 실제 운영 사건의 구절·값으로 해석하지 않음.
- #357 전용 worktree의 미커밋 변경이며 운영 배포·실제 Discord 발송·기존 알림 변경은 수행하지 않음.
