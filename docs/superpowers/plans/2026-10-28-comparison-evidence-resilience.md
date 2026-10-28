# Comparison Evidence Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 2026-10-28 사용자 승인. 제품 코드 구현은 gpt-6.1-sol / max로 수행하며 운영 재실행은 허가하지 않는다.

**Goal:** 인용 부호 오차 때문에 기존 설정 비교 전체가 실패하지 않게 하고, 실제 실패의 단계·원인을 Discord에서 확인한다.

**Architecture:** 새 추출은 제한된 문자 차이의 유일한 원문 구간만 복원한다. 기존 확정값 읽기는 일반 인용 확인 불가를 명시적으로 분리한다. 안전한 구조화 진단을 Java → Python → 실패 저장 → PostgreSQL outbox → Discord로 전달한다.

**Tech Stack:** Java 21 / Spring Boot / JPA·JdbcTemplate / PostgreSQL·Flyway / Python·Pydantic·httpx / pytest / JUnit·Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-28-comparison-evidence-resilience-design.md` (반드시 함께 읽는다).

## Global Constraints

- 운영 근거 수정·백필·재분석·재비교·확정/제외는 실행하지 않는다.
- Backend `4b68365aff7ca58265f43bab0c7f08a276765371`, AI `fa87321444af3724548f50c73c9694bd88d92817`에서 시작한다. 양쪽 브랜치는 `fix/gh-304-comparison-evidence-resilience`다.
- 아래 B/ 경로는 `backend/`, A/ 경로는 형제 `ai/` worktree 기준이다.
- 인용 검색 허용 차이는 공백 및 `U+0022`, `U+201C`, `U+201D`뿐이다. 최종 quote/offset은 정확한 원문 slice여야 한다.
- 현재 확정값 보존과 원문 근거의 VERIFIED 여부는 별개다. 새로운 확정·시점·원문 hash·작품/Job 연결 검증을 완화하지 않는다.
- 실제 원고, 실제 사용자·작품·캐릭터 식별 정보를 Git fixture/문서에 넣지 않는다. 읽기 전용 사용자 현황 보고서는 개인 정보가 포함되어 Git 밖에 보관한다.
- Discord 상세 진단에 원시 error_message, stack trace, 자유 텍스트, URL, 인증값, 연락처, 원고, 설정값을 넣지 않는다.
- 새로운 외부 서비스/라이브러리, LLM 추가 호출, 전체 후보 자동 재분석, 과거 실패 알림의 임의 백필은 도입하지 않는다.
- 단위/계약 검증과 실제 모델 품질 평가는 구분한다. 운영 사용자의 원고로 유료 평가하지 않는다.

## Review Focus

1. 동일한 정규화 문장이 두 곳인 경우 다른 사건에 근거를 연결하지 않는다 — Task 1의 ambiguous test.
2. 일반 근거와 시점 근거가 같은 후보에 있는 경우 일반 인용 격리가 시점 검증을 우회하지 않는다 — Task 2의 mixed-evidence test.
3. 기존 확정 읽기와 신규 journal 후보가 같은 resolver를 사용하는 경우 관대한 정책이 새 입력으로 새지 않는다 — Task 2의 retained/new-mode tests.
4. 후보 실패 후 일반 Job wrapper가 생기는 경우 첫 실패의 원인·단계가 사라지지 않는다 — Task 3의 worker wrapping test.
5. 같은 트랜잭션의 서로 다른 원인/실행, 재시도와 구 Worker가 섞이는 경우 outbox 원인이 오염되지 않는다 — Task 4의 PostgreSQL grouping/snapshot/legacy tests.

## 준비 완료 기록

- [x] 기존 체크아웃 상태를 확인하고 최신 origin/main을 fetch했다.
- [x] 두 저장소에 새 worktree를 만들었다. 기존 apps의 브랜치·작업 파일은 변경하지 않았다.
- [x] Backend 관련 기준 테스트 72개, AI 관련 기준 테스트 74개 통과했다.
- [x] 운영 사용자 현황을 READ ONLY로 집계하고 SSM 세션을 종료했다.
- [x] 2026-10-28 사용자 승인. 이슈 https://github.com/catchhole-soma/catchhole-backend-java/issues/304 생성.

## Task 1: 새 추출의 인용 부호 차이를 정확한 원문 구간으로 복원

**Files:**
- Modify: A/app/analysis/evidence_span_resolver.py
- Test: A/tests/test_evidence_span_resolver.py
- Test: A/tests/test_book_character_evidence.py, A/tests/test_book_world_evidence.py

**Interfaces:**
- 유지: `resolve_evidence_span_offsets(span: ExtractedEvidenceSpan, chunk_text: str, chunk_start_offset: int) -> ExtractedEvidenceSpan`.
- 추가: `_find_quote_normalized_range(chunk_text: str, quote: str) -> tuple[int, int] | None`.
- 기존 `_NormalizedText(text, ranges)`를 재사용해 정규화된 문자의 원문 경계를 보존한다. helper는 유일한 구간만 반환한다.

- [ ] **Step 1: 실패 회귀 테스트 작성.** 합성 대화의 질문 끝 큰따옴표 누락, curly/ASCII 차이, 공백+부호 차이, emoji 앞 offset, 복수 span을 검사한다. 결과 quote가 `source[start:end]`와 정확히 같고 전역 offset이 맞아야 한다. 기존 exact/공백 결과는 같아야 한다.
- [ ] **Step 2: 보정하지 않는 경우 테스트 작성.** 같은 문장 두 곳, 빈 정규화 문자열, 숫자·부정어·작은따옴표·일반 문장부호 변화에는 null offset/원 quote를 유지한다. raw AI 입력 객체를 바꾸지 않는 것을 확인한다.
- [ ] **Step 3: `python -m pytest tests/test_evidence_span_resolver.py -q`로 신규 사례만 실패하는지 확인한다.** 운영 모델/DB를 호출하지 않는다.
- [ ] **Step 4: helper를 구현하고 exact → 공백 → 부호 차이 순서로 연결한다.** 검색 정규화 결과를 저장하지 않고 원문 slice로 새 span을 만든다. source chunk 경계를 넘겨 검색하지 않는다.
- [ ] **Step 5: 위 resolver 및 두 book evidence 테스트를 실행한다.** 성공 후 인용 복원 변경만 별도 커밋한다.

## Task 2: 기존 확정 근거의 일반 인용 오류를 비교 문맥에서 격리

**Files:**
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/analysis/processor/SettingBookAutomaticEvidenceValidator.java
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/character/processor/CharacterCurrentSupportingEvidenceResolver.java
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/character/service/CharacterAnalysisStateSource.java
- Audit/Modify only strict caller separation: B/src/main/java/org/monitoring/catchholebackend/domain/character/service/CharacterAnalysisStateService.java
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/character/mapper/CharacterAdvisoryCurrentContextMapper.java
- Modify: A/app/llm/prompts/character_fact_comparison_batch.md, A/app/analysis/character_fact_comparator.py (`CHARACTER_FACT_COMPARISON_BATCH_CACHE_KEY` v10 → v11, 실행 직전 최신 번호 확인)
- Test: B/src/test/java/org/monitoring/catchholebackend/domain/analysis/processor/SettingBookAutomaticEvidenceValidatorTest.java
- Test: B/src/test/java/org/monitoring/catchholebackend/domain/character/service/CharacterCurrentSupportingEvidenceCaptureTest.java
- Test: B/src/test/java/org/monitoring/catchholebackend/domain/character/service/CharacterFactComparisonBatchWorkerTest.java
- Test: B/src/test/java/org/monitoring/catchholebackend/domain/upload/controller/FrozenCurrentCaptureFailureIntegrationTest.java
- Test: A/tests/test_character_fact_batch.py

**Interfaces:**
- `EvidenceValidationResult validate(String content, JsonNode evidence)`; 결과는 `VALID`, `QUOTE_NOT_FOUND`, `MALFORMED_SPANS`, `INVALID_OFFSETS`. 기존 `boolean matches(...)`는 VALID 여부를 반환한다.
- `ObjectNode captureRetainedCandidate(Capture capture, SettingCandidate candidate)`를 추가한다. 기존 `captureCandidate`는 신규/엄격 경로를 유지한다.
- 기존 supporting origin 형태를 유지하고 `availability=UNAVAILABLE`, `unavailableReason=QUOTE_NOT_FOUND`, `evidenceSpans=[]`만 새 허용 사례로 추가한다.

- [ ] **Step 1: capture 호출을 분류한다.** 실제 DB current/history Fact, 확정 discovery, 이미 저장된 currentNarrativeTime source의 일반 인용은 retained, 신규 후보/journal/provisional/시점 승격은 strict로 표를 남긴다. 대표 시점 자체의 근거와 저장된 referenceTime 검증은 별도로 strict를 유지한다. `reviewStatus` 하나로 분기하지 않는다.
- [ ] **Step 2: 검증 결과 및 retained 회귀 작성.** 합성된 이미 확정된 Fact가 quote-only 불일치/null offset일 때 값·source IDs가 보존되고 해당 source의 quote만 전달되지 않는지 검사한다. 정상 다른 source와 다른 캐릭터가 남아 있어야 한다.
- [ ] **Step 3: strict 회귀 작성/수정.** 신규 quote 오류, 다른 작품/Job/인물, 명시 offset 불일치, 반쪽 offset, JSON 구조 오류, 원문 hash 오류, 저장소 실패, narrativeTime 오류는 계속 실패해야 한다. 기존 forged-quote 테스트는 retained 문맥에서 unavailable이고 신규 문맥에서는 거절됨을 각각 명시한다. `retainedRepresentativeTimeWithInvalidGeneralQuoteAndValidTimeEvidenceContinues`와 `retainedRepresentativeTimeWithInvalidTimeEvidenceStillFails`를 별개 테스트로 둔다.
- [ ] **Step 4: 대상 테스트를 실행해 red를 확인한 뒤 검증 결과·retained 경로·reason mapper를 구현한다.** frozen input/hash를 만든 후의 지연 보정은 넣지 않는다.
- [ ] **Step 5: batch context 통합 회귀로 HTTP 준비 성공을 검증한다.** 기존 Fact를 재확정하거나 DB 인용을 고치지 않고 새 비교 입력이 만들어져야 한다. 합성 비교 응답으로 결과 저장까지 확인한다. 정상 검토 필요(REVIEW_REQUIRED)는 성공/실행 실패와 구분한다.
- [ ] **Step 6: 프롬프트에 근거 확인 불가와 현재값의 차이를 명시하고 버전을 갱신한다.** `UNAVAILABLE`가 전달되는 prompt 계약 테스트를 추가한다.
- [ ] **Step 7: 관련 Java 테스트와 `python -m pytest tests/test_character_fact_batch.py -q`를 실행하고 별도 커밋한다.** 실제 모델 성공률을 주장하지 않는다.

## Task 3: 알려진 원인과 HTTP 진단을 실패 저장까지 보존

**Files:**
- Create: B/src/main/java/org/monitoring/catchholebackend/domain/analysis/dto/AnalysisFailureDiagnostics.java
- Create: B/src/main/java/org/monitoring/catchholebackend/domain/analysis/processor/AnalysisFailureDiagnosticsSanitizer.java
- Modify: B/domain/analysis/exception/AnalysisJobErrorCode.java, B/global/exception/GlobalExceptionHandler.java (이 Task의 B/ 축약은 `src/main/java/org/monitoring/catchholebackend/` 아래)
- Modify: B/domain/character/processor/CharacterCurrentSupportingEvidenceResolver.java
- Modify: B/domain/analysis/dto/request/WorkerAnalysisJobFailRequest.java
- Modify: B/domain/character/dto/request/WorkerCharacterFactComparisonFailRequest.java, WorkerCharacterFactComparisonBatchCompleteRequest.java의 Failure record
- Modify: B/domain/worldsetting/dto/request/WorkerWorldSettingComparisonFailRequest.java, WorkerWorldSettingComparisonBatchCompleteRequest.java의 Failure record
- Modify: B/domain/analysis/entity/AnalysisJob.java, B/domain/character/entity/SettingCandidate.java, B/domain/worldsetting/entity/WorldSettingCandidate.java, 비교 묶음 entity
- Modify: B/domain/analysis/service/AnalysisJobWorkerServiceImpl.java, B/domain/character/service/CharacterFactComparisonWorkerServiceImpl.java, CharacterFactComparisonBatchWorker.java, B/domain/worldsetting/service/WorldSettingWorkerServiceImpl.java, OrderedWorldSettingWorker.java
- Create: B/src/main/resources/db/migration/V89__add_analysis_failure_diagnostics.sql
- Modify: A/app/schemas/worker.py, A/app/clients/exceptions.py, A/app/clients/spring_worker_client.py, A/app/clients/safe_http_diagnostics.py
- Modify: A/app/exceptions/failure_classification.py
- Modify: A/app/analysis/character_fact_comparison_pipeline.py, A/app/analysis/world_setting_pipeline.py, A/app/analysis/world_setting_manuscript_pipeline.py
- Modify: A/app/worker/analysis_job_worker.py, A/app/worker/character_fact_comparison_worker.py, A/app/worker/world_setting_comparison_worker.py
- Tests: B/src/test/java/org/monitoring/catchholebackend/domain/analysis/controller/AnalysisJobWorkerControllerIntegrationTest.java, B/src/test/java/org/monitoring/catchholebackend/domain/character/service/CharacterFactComparisonWorkerServiceImplTest.java, B/src/test/java/org/monitoring/catchholebackend/domain/worldsetting/controller/WorldSettingWorkerControllerIntegrationTest.java, A/tests/test_safe_spring_http_diagnostics.py, A/tests/test_spring_worker_client.py, A/tests/test_failure_classification.py, A/tests/test_character_fact_comparison_worker.py, A/tests/test_world_setting_comparison_worker.py, A/tests/test_analysis_job_worker.py

**Interfaces:**
- Java record/Python model `AnalysisFailureDiagnostics` / `WorkerFailureDiagnostics`: spec의 v1 필드를 정확히 공유한다. JSON aliases는 camelCase다.
- Python `failure_diagnostics(exc: BaseException, *, stage: str) -> WorkerFailureDiagnostics | None`는 cause chain의 가장 구체적인 허용 진단을 반환한다.
- 각 fail request에 optional `failureDiagnostics`를 추가한다. Java 입력은 nullable JsonNode이고 sanitizer는 잘못된 진단만 버려 실패 저장을 유지한다. 내부 저장/반환 모델만 typed record를 사용한다. client 메서드에는 끝의 keyword-only `failure_diagnostics=None`을 추가해 기존 호출 호환을 유지한다.
- run result와 batch stats에는 optional `first_failure_diagnostics`를 추가해 첫 실패 코드와 쌍으로 전달한다.
- DB: Job/비교 묶음은 `failure_diagnostics jsonb`, 두 후보는 `comparison_failure_diagnostics jsonb`로 같은 실패 전이에 저장한다. nullable additive migration이며 기존 실패를 추정해서 backfill하지 않는다.

- [ ] **Step 1: Java ↔ Python 공통 합성 JSON fixture를 작성한다.** 모든 허용 필드, legacy null, UNKNOWN fallback, 금지 필드·악성 mention/URL/원문·긴 문자열을 검사한다. `malformedOptionalDiagnosticsStillRecordsFailure`, `unsupportedDiagnosticVersionStillRecordsFailure`, `foreignSourceIdentifiersAreRemovedWithoutRejectingFailure`를 포함한다. diagnostics 때문에 기존 실패 보고가 400으로 거절되지 않게 허용되지 않는 부가 필드는 버리고 본래 실패는 기록한다.
- [ ] **Step 2: resolver의 알려진 실패를 AppException/코드/context로 전환하는 테스트를 작성한다.** 409 validation, 503 storage, UNKNOWN 500이 구분돼야 한다. 기존 first-claim 실패의 journal/lease/token 정리 및 다음 작품 claim 회귀를 보존한다.
- [ ] **Step 3: DTO/검증기/오류 매핑과 additive persistence를 구현한다.** 서버가 origin 후보·Job·회차의 동일 작품 연결을 확인한 경우만 식별자를 저장한다. 원인 식별자 하나가 이상해도 coarse 실패 기록을 잃지 않는다.
- [ ] **Step 4: AI 파싱·전파 red tests 후 구현한다.** context 실패→batch fail→일반 wrapper→Job fail 과정에 reason/stage/HTTP/request/trace가 유지되고 첫 실패와 다른 실패의 진단이 섞이지 않아야 한다. provider 오류와 Spring 오류의 분류/재시도 정책은 같아야 한다.
- [ ] **Step 5: 진단 reset·멱등·stale Job 테스트를 수행한다.** 이전 시도의 진단이 새 실패로 재사용되지 않고 obsolete Job은 현재 후보를 변경하지 않아야 한다. lease token은 payload나 outbox 진단에 들어가지 않아야 한다.
- [ ] **Step 6: 위 Python 6파일과 Java worker/controller 테스트 실행 후 양쪽을 계약 단위로 커밋한다.** 실제 migration 번호는 구현 직전 origin/main의 다음 번호로 재확인한다.

## Task 4: 실패 당시 진단을 outbox에 고정하고 Discord 내용 개선

**Files:**
- Create: B/src/main/resources/db/migration/V90__snapshot_analysis_failure_notification_diagnostics.sql
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/analysis/dto/AnalysisFailureNotification.java
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/analysis/repository/AnalysisFailureNotificationRepository.java
- Modify: B/src/main/java/org/monitoring/catchholebackend/domain/analysis/mapper/AnalysisFailureNotificationMapper.java
- Test: B/src/test/java/org/monitoring/catchholebackend/domain/analysis/service/AnalysisFailureNotificationPostgresIntegrationTest.java
- Create Test: B/src/test/java/org/monitoring/catchholebackend/domain/analysis/mapper/AnalysisFailureNotificationMapperTest.java
- Regression: B/src/test/java/org/monitoring/catchholebackend/domain/analysis/processor/AnalysisFailureNotificationDispatcherTest.java, B/src/test/java/org/monitoring/catchholebackend/global/notification/DiscordWebhookClientTest.java

**Interfaces:**
- outbox에 optional `failure_diagnostics`, `execution_job_id`, `source_analysis_job_id`, `comparison_batch_id`, `job_type` snapshot을 추가한다. 기존 `analysis_job_id`는 호환을 위해 보존한다.
- reason/stage/실행/묶음을 포함하는 그룹 키를 도입한다. 진단 없는 writer의 그룹 의미는 기존과 같다. 같은 원인에 묶인 개수와 여러 원인을 대표 하나로 축약한 개수를 혼동하지 않는다.
- `toMessage(AnalysisFailureNotification event, String environment)`는 signature를 유지하고 상세 reason별 고정 한글 설명과 재시도 판단을 제공한다. 필수 식별자·원인·단계를 우선하여 2,000자 이내로 만든다.

- [ ] **Step 1: PostgreSQL red tests 작성.** 실패와 진단의 원자 저장, rollback=알림 없음, 후보 여러 개 동일 원인 그룹, 같은 tx의 다른 reason/실행 분리, old writer, 재시도 후 재실패, stale completion, 여러 회차 그룹을 검증한다.
- [ ] **Step 2: 새 migration과 DTO/repository를 구현한다.** 원인 metadata는 실패 당시 row에서 복사하고 발송 때 현재 후보/Job으로 보충하지 않는다. current FK로 더 이상 존재하지 않는 원인을 억지 복원하지 않는다.
- [ ] **Step 3: formatter red tests 작성 후 구현한다.** 실행 Job과 원본 추출 Job, 대상 회차와 원인 회차, 실패 작업 수와 후보 수, coarse code와 상세 reason을 구분한다. 알 수 없는 값은 ‘확인 불가’, 일반 500도 단계/추적 ID가 있으면 출력한다.
- [ ] **Step 4: 알림 payload의 원문·연락처·URL·인증·예외 본문·mention 차단, 최대 길이, old row fallback을 검증한다.** 실제 Discord webhook을 호출하지 않는다.
- [ ] **Step 5: `./gradlew test --tests '*AnalysisFailureNotification*' --tests '*DiscordWebhookClientTest'` 실행.** PostgreSQL Testcontainers 테스트가 SKIPPED이면 통과로 보고하지 않는다. 동시 선점·재시작·429/backoff 및 실패 저장 후 늦은 상태 변경 회귀를 확인하고 커밋한다.

## Task 5: 전체 검증·문서·검토용 변경 묶음

**Files:** B/AGENTS.md, A/AGENTS.md, B/docs/analysis-failure-notifications.md, B/docs/analysis-continuity.md, A/docs/ai-logic-versions/README.md, A/docs/ai-logic-versions/v0024.md, A/docs/ai-logic-versions/details/v0024.md (번호는 구현 완료 직전 최신 main 기준 재확인).

- [ ] **Step 1: 합성 end-to-end 검증.** 과거 확정 quote 누락+새 후보→context 정상→비교 결과 저장, source hash 오류→typed 실패→Python 보고→DB outbox→mock Discord까지 연결한다. 신규/기존 evidence와 source purge, emoji offset, 재시도 snapshot을 함께 확인한다.
- [ ] **Step 2: rolling 호환 검증.** 신 Backend/구 Worker는 진단 없이 동작하고 신 Backend/신 Worker는 진단이 보존된다. 신 Worker가 구 Backend에서 optional 필드를 거절당하지 않는지는 실제 구 계약으로 확인하며, 배포 순서는 Backend 먼저로 고정한다.
- [ ] **Step 3: Backend `./gradlew test bootJar`, AI `python -m pytest -q` 실행.** 실제 실행 수·실패·skip과 환경을 기록한다. 실패하면 수정 후 영향 범위를 재검증한다.
- [ ] **Step 4: AGENTS·정책·알림 예시·AI version 문서를 갱신한다.** 합성 회귀와 실제 모델 미측정을 명확히 쓰고, 공개 DTO의 coarse failure code 유지와 internal 상세 진단 계약을 설명한다.
- [ ] **Step 5: 두 저장소 diff를 검토하고 PR 준비.** 각 PR은 문제/변경/검증/호환성을 설명하고 서로 연결한다. 제품 수정 전에 계획 승인을 받고, 배포는 구체 변경과 검증이 준비된 뒤 별도 단계로 다룬다. 운영 Job을 테스트 목적으로 재실행하지 않는다.

## 실행 순서와 종료 조건

Task 1·2는 인용 장애 예방, Task 3·4는 실패 추적 개선이며 Task 5에서 함께 확인한다. 구현은 이 대화에서 순차 진행하는 방식을 권고한다. 계약이 서로 연결되어 있어 작업마다 별도 병렬 구현을 하는 이득이 작다. 최종 독립 리뷰는 진행 방식 확정 후 수행한다.

종료 조건은 spec의 다섯 완료 기준과 위 검증 충족이다. worktree 생성·기준 테스트 통과·계획 작성만으로 버그 수정 또는 운영 복구 완료를 선언하지 않는다.
