# #310 검사 실행·재검수 관리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 기존 설정·오탈자 검사기를 실제 검수 실행, 중간 저장, 같은 입력 재시도와 수정 후 재검수에 연결한다.

**Architecture:** Java는 Work 잠금 아래 현재 report와 불변 입력, 전용 검수 Job/lease, 실행·사용량·결과 저장을 소유한다. Python은 같은 고정 입력으로 설정·오탈자를 독립 병렬 실행하고 검증된 계획·판정·구간을 순차 게시한다. 원고/기준 변경은 새 report, 같은 입력의 중단은 기존 report의 미완료 부분 재시도로 구분한다.

**Tech Stack:** 기존 Java 21 / Spring Boot / JPA / Flyway / PostgreSQL, Python 3.12 / asyncio / Pydantic / httpx / pytest.

**Spec:** 공용 issue https://github.com/catchhole-soma/catchhole-backend-java/issues/310, 로컬 스냅샷 ../../../../../issue-310.md. 제품 상세는 Front worktree의 docs/planning/gh-300-validation-baseline-design.md, gh-300-validation-stage-contracts.md, gh-300-error-report-implementation-plan.md Task 2 및 Task 4 중 #310 범위다. 구현된 #306 docs/setting-validation-v1.md와 #309 app/validation/README.md도 읽는다.

## Global Constraints

- 사용자 승인 범위는 #310 전체다. 기획 재승인 없이 구현하되 #312 판단/검토 종료/추출 release와 #314 UI·다음 회차 등록 제한은 제외한다.
- 구현 gpt-6.1-sol / max, 검토 gpt-6-astra / xhigh. 구현자는 subagent를 생성하지 않는다. 단계별 focused test와 task review 뒤 다음 단계로 진행한다.
- Backend 작업 경로: /Users/junewoo/Developer/캐치홀 프로젝트 코덱스/output/gh-310-validation-execution/worktrees/backend (feat/gh-310-validation-execution). 시작 main 5b77f97718ac7ddc3f34d5cc8dd0b6e0b0a77db4.
- AI 작업 경로: /Users/junewoo/Developer/캐치홀 프로젝트 코덱스/output/gh-310-validation-execution/worktrees/ai (codex/gh-310-validation-execution). 시작 main c1b54a8e139c1d26b1307616f76cbae6984b7836.
- Front 명세 경로: /Users/junewoo/Developer/캐치홀 프로젝트 코덱스/output/gh-300-error-report/worktrees/frontend. 읽기만 한다.
- Python 실행기: /Users/junewoo/Developer/캐치홀 프로젝트 코덱스/apps/CatchHole-AI/.venv/bin/python. AI worktree에서 PYTHONPATH=.를 명시한다. 공유 venv/원본 checkout/.env를 수정하지 않는다.
- 모든 상태에서 추출 Job·후보 생성·설정 확정·자동 철회 쓰기는 0개. 진단 재검수는 실제 기존 확정 기록을 참조하고 클라이언트가 모드를 고르지 않는다.
- 검수용 별도 Job을 사용하며 가짜 AnalysisJob, 가짜 lease, 테스트 전용 정상 완료 endpoint를 제품에 만들지 않는다.
- 모델/추론은 기존 Terra PLANNER medium / COVERAGE high / JUDGE medium / TYPO low. count와 generation에 동일 옵션·schema; 검수 retry는 실제 호출 예산·원장 안에서만 허용한다.
- 실제 유료 모델 실행, 운영 배포/환경 변경/공유 DB 쓰기, 다른 채팅 메시지는 이 작업에 포함하지 않는다. 로컬 격리 PostgreSQL와 합성 provider/S3로 실제 서비스 연결을 검증한다.
- 검색/시간/호출 수를 사용자 합의된 운영 기본값으로 발명하지 않는다. 명시적 실행 설정으로 주입하고 미설정이면 검수 runner 실행을 거절한다. 테스트값은 합성 fixture로 표시한다.
- 현재 report 선택, 원고·기준 fingerprint, 실행 세대, lease, check revision/input digest, 인용 Unicode code point를 서버에서 검증한다. 원문/비밀값/원시 예외를 로그에 남기지 않는다.
- 기존 일반 설정 구축·추출/챗봇 기본값은 유지한다. 이미 배포한 V91과 과거 migration은 수정하지 않고 후속 migration을 추가한다.
- 전체 테스트는 통합 완료 후 한 번 실행한다. 단계 중에는 변경 관련 focused/regression을 사용하고 새 실패/수정 때만 반복한다. skip/기존 경고/기존 lint 실패를 통과와 분리한다.
- 로컬 의미 단위 commit은 허용하며 commit-management 규칙을 따른다. push/draft PR은 controller가 최종 검증 뒤 처리한다. #310 merge는 별도 사용자 요청 없이 수행하지 않는다.

## Review Focus

1. A→B→A 원고/설정 revision 변경은 같은 문자열이어도 이전 lease/report/checkpoint를 되살리지 않는다 (Task 1+2).
2. 한 질문의 새 단서가 이미 정상 완료한 다른 질문을 바꾸면 관련 revision만 올리고 옛 완료를 제외한다 (Task 3).
3. 늦은 예약 정산은 유실하지 않되 만료 lease/무효 report는 새로운 provider 예약·본문 게시를 하지 못한다 (Task 2).
4. 설정 질문 0개/설정 실패와 오탈자 실패를 분리하고 0건을 완료로 추측하지 않는다 (Task 2+3).
5. 결과 저장 응답 유실·계획 저장 직후 crash·재claim은 중복 게시/정산 없이 같은 계획과 성공 check를 재사용한다 (Task 2+3).

---

### Task 1: 재검수 이력·현재 report·명시적 기준 정정

**Files:**
- Modify Java domain/validation/entity/ValidationReport.java, repository/ValidationReportRepository.java, ValidationRequestKey.java, service/EpisodeValidationService.java, ValidationBaselineService.java, controller/EpisodeValidationController.java, 관련 DTO/type/mapper.
- Create Java domain/validation/service/ValidationRecheckService.java 및 Impl, ValidationCorrectionService.java 및 Impl; entity/ValidationCorrection.java, repository와 typed request/receipt.
- Modify 기존 CharacterServiceImpl / WorldSettingServiceImpl 편집 경계와 필요한 request DTO; episode/upload/source purge와 WorkPurgeDataRepository.
- Create migration V92__validation_report_rechecks.sql (현재 번호 재확인), docs/validation-execution-v1.md와 공통 fixture의 접수 계약; AGENTS.md 관련 규칙.
- Test domain/validation/ValidationRecheckIntegrationTest.java, ValidationCorrectionIntegrationTest.java, ValidationRecheckPostgresIntegrationTest.java.

**Interfaces:**
- Reuse start(memberId, workId, ValidationStartRequest) / get(memberId, workId, reportId), 기존 접수 멱등.
- Produce recheckValidation(memberId, workId, reportId, ValidationRecheckRequest) -> ValidationStagingResponse. 요청: expectedReportVersion, 현재 target hash/key/version, expectedBaselineHash 또는 정정 receipt 참조, idempotencyKey. 같은 키 다른 입력은 409.
- Produce correctSettingAndLinkBaseline(memberId, workId, reportId, ValidationCorrectionRequest) -> ValidationCorrectionReceipt. 실제 기존 편집 command와 baseline entry/hash/revision·현재 entity version·정정 대상 경로를 함께 검증한다.
- Produce report.current/report.revision/report.mode/previousReport 및 실제 이전 적용 식별값, 버전 충돌을 검사할 current report 조회, report invalidation 통지 경계. Task 2에서 결과 저장소를 이 경계에 연결한다.

- [ ] 기존 전체 규칙/실제 schema·편집·확정 이력 소비자를 읽고 현재 report 조회를 쓰는 모든 SQL/projection/추출 gate를 목록화한다. 단일 report 가정이 있는 목록 집계도 바꾼다.
- [ ] failing tests: changedManuscriptCreatesNewReportWithoutExtraction, recheckAdmissionIsIdempotent, sameKeyDifferentInputRejected, staleReportVersionRejected, baselineCorrectionIsAtomicWithActualEdit, ordinarySaveDoesNotCreateCorrection, ambiguousOrHistoricalCorrectionRejected. 새/기존 report 포인터와 추출 Job/후보 0을 assert.
- [ ] ./gradlew test --tests '*ValidationRecheckIntegrationTest' --tests '*ValidationCorrectionIntegrationTest'로 기대 실패를 기록한다.
- [ ] episode_id UNIQUE를 현재 report/이력 계약으로 확장한다. Work 잠금 아래 최신 회차 및 기존 접수 자격을 구분하고 새 report를 원자 생성한다. 일반 저장은 무효화만 하며 재검수/추출을 자동 시작하지 않는다. target만 바뀐 경우 유효한 기준을 재사용할 수 있지만 파기된 사본은 복원하지 않는다.
- [ ] 기존 편집 service를 재사용해 명시적 정정만 실제 CAS 결과와 원자 연결한다. character 전체 PUT의 다른 항목 덮어쓰기/동시 수정도 차단한다. 현재값과 맞지 않는 과거 기준의 baseline-only 철회는 거절한다. 서버가 기록한 범위만 새 기준의 explicitAuthorCorrection으로 전달한다.
- [ ] 이미 확정한 최신 회차 재검수는 실제 적용/확정 이력에 근거해 DIAGNOSTIC_ONLY로 정한다. 새 추출/확정/철회는 0. APPLIED 후속 기능이 아직 없어도 구형 실제 확정 기록과 모순되지 않는 범위를 문서화한다.
- [ ] 원문/설정집 revision 변경·파기, 현재 설정 변경, 작품 삭제·회원 탈퇴에 사본/정정 정리를 연결한다. delayedMaterializationCannotRestorePurgedText, replacementABARejectsOldReport, staleCorrectionRollsBackAllEdits를 격리 PostgreSQL에서 검증한다.
- [ ] focused 및 기존 ErrorFirstValidation/ValidationData/편집·삭제 회귀를 실행하고 skip과 실제 PG를 분리 기록한다. docs/AGENTS와 의미 단위 commit을 작성한다.

### Task 2: 검수 전용 Job·lease·사용량·결과 저장과 재시도 API

**Files:**
- Create Java domain/validation entity/repository/type의 ValidationJob, 실행 분기 상태, plan/check/finding/evidence/coverage/checkpoint 및 필요한 정규 저장 모델. 한 책임별 service/processor/mapper와 DTO를 사용한다.
- Create InternalValidationWorkerController, ValidationWorkerService 및 Impl, ValidationResultService 및 Impl.
- Modify InternalValidationDataController/ValidationDataService의 실행 scope 검증, Task 1 무효화·삭제 경계.
- Modify domain/aitoken의 사용량 주체와 전용 purpose/예약 검증, 기존 account/reserve/settle/release 재사용.
- Create 후속 Flyway migration, docs/validation-execution-v1.md 및 docs/contracts/validation-execution-v1.json; API 문서/AGENTS.
- Test ValidationWorkerIntegrationTest, ValidationExecutionPostgresIntegrationTest, 검수 사용량/무효화 테스트.

**Interfaces:**
- Consume Task 1의 current report/version/mode, 고정 baseline, 무효화 통지, ValidationDataService.
- Produce dedicated claimValidationJob(capabilities), heartbeatValidationJob(jobId, lease), loadValidationCheckpoint(jobId, lease), publishValidationPlan(jobId, lease, command), publishValidationUnit(jobId, lease, command), completeValidationJob(jobId, lease, command), retryValidation(memberId, workId, reportId, expectedVersion, idempotencyKey).
- Produce versioned JSON fixture containing exact routes and DTOs for Task 3. Payload는 contractVersion, report/job/lease/attempt, 서버 member/work scope, target/baseline/manifest digest, 역할별 budget, checkpoint를 포함하며 모델에는 서버 권한 값을 전달하지 않는다.
- Produce validation token ledger reserve/settle/release. 기존 analysis_job_id에 검수 UUID를 가장하지 않고 실제 validation Job 소유권을 저장한다.

- [ ] 먼저 failing tests로 lease 만료/회수·다른 작품·이전 report·중복 입력을 거절하고 모든 API에서 추출 Job/후보 0을 검증한다.
- [ ] Job/lease/heartbeat와 한 회차의 현재 실행 세대를 만든다. 초기 명시 접수/재검수에서 준비된 report를 queue하며 기존 대기 report 처리는 명시 접수/재시도 경로로 설명한다. 서버가 실제 준비 상태를 확인하고 lease를 원자 claim한다.
- [ ] 원장 목적별 사용량을 분리하고 실제 계정 잔액/실행 상한/lease를 예약 전 검증한다. 응답 유실·실패·취소·늦은 정산의 기존 원장 의미를 유지한다. 가짜 AnalysisJob을 만들지 않는다.
- [ ] 계획·완료 NO_CONFLICT·finding/근거·coverage와 checkpoint를 입력 digest/세대/check revision에 연결한다. 서버 발급 안정 check ID 및 재전송 receipt를 반환한다. 같은 key 다른 payload와 늦은 revision은 거절한다.
- [ ] 결과 게시 전 출처/실제 인용 위치·원문 hash를 검증하고 잠금 내 재확인한다. DB I/O와 provider 호출을 같은 긴 잠금에 두지 않는다. 출처 변경 뒤 조회/복사본/checkpoint를 정리하고 늦은 게시를 막는다.
- [ ] 성공 일부+미완료는 PARTIAL, 성공 처리 0+실패는 FAILED, 양쪽 모든 필수 coverage+check 완료만 COMPLETED. 정상 0건도 저장된 완료 기록이 필요하다. 실행 중 읽기 전용 결과 조회와 중단 후 조회를 제공하되 작가 판단 API는 추가하지 않는다.
- [ ] 같은 입력 retry는 같은 report와 성공 기록을 보존해 새 lease로 미완료만 처리한다. 새 단서로 의미가 달라지는 항목 revision/변경 사유를 저장하고 #312가 이전 판단과 구분할 수 있게 한다.
- [ ] 실제 격리 PostgreSQL에서 lease 경쟁, 계획 직후 crash, 중복 게시, 파기와 늦은 게시, quota/정산, source ABA를 실행한다. 관련 H2/auth/serialization 회귀와 계약 fixture를 검증한 뒤 commit한다.

### Task 3: Python 실행·계획/질문 재개·독립 병렬 검사와 실제 연결 검증

**Files:**
- Create app/validation/execution.py, checkpoint.py (typed stage state), client.py 및 app/worker/episode_validation_worker.py.
- Modify app/validation/service.py / typo.py의 optional persistence/resume seam, data.py의 lease-scoped transport, 필요 시 contracts.py. 기존 호출형 API는 호환 유지한다.
- Modify app/usage/metering.py는 필요한 최소 범위에서만 실제 검수 ledger를 지원한다. 공통 추출/챗봇 retry/default는 유지한다.
- Create scripts/run_validation_worker.py 또는 기존 runner의 명시적 validation kind; 실행 설정, README/AGENTS, 공통 계약 fixture, v0028 사람용/상세/목록(번호 재확인).
- Test tests/test_episode_validation_worker.py, test_validation_checkpoint.py, 기존 setting/typo 회귀, tests/support/validation_execution_http_client.py 및 Java ValidationExecutionHttpPostgresIntegrationTest.

**Interfaces:**
- Consume Task 2 docs/contracts/validation-execution-v1.json의 실제 route/DTO를 그대로 구현한다.
- Preserve SettingValidationService(data_client, model_client, budget, ...).inspect()와 check_typos(data, llm, budget=...)의 기존 호출형 소비자. Worker에서는 typed checkpoint reader/writer를 추가 주입한다.
- Produce EpisodeValidationWorker.claim_next()/process_claimed(payload)/aclose()와 명시 실행 CLI. 서버 상태를 직접 DB에 써 우회하지 않는다.

- [ ] 실패 테스트: 계획 저장 후 강제 종료/재claim 시 PLANNER/COVERAGE 재호출 0; 완료 NO_CONFLICT JUDGE 재호출 0; 실패 질문/구간만 처리; affected question revision만 재판정; 바뀐 원고/lease는 게시 0.
- [ ] 설정 검사를 단계별 저장 가능한 의미 상태로 최소 분리한다. 실제 검증한 계획을 판정 전에 게시하고 성공 질문은 즉시 게시한다. 최종 한 덩어리 저장이나 LLM 응답 cache만으로 재시도 구현을 대체하지 않는다. 실패/부분 coverage와 후속 확인 queue·근거·digest를 복원한다.
- [ ] 오탈자는 성공 구간의 category·정확한 위치를 보존하고 실패 구간만 재개한다. 원문 교체 시 전부 새 입력이다. 기존 raw/exact metered(max_retries=0) 경계와 최종 source 검증을 유지한다.
- [ ] 두 분기를 독립 asyncio task로 실행하고 양쪽 실패/예산/사용량을 분리한다. 한 분기 실패/질문 0개로 다른 분기를 생략·취소하지 않는다. 전체 취소/lease 손실에는 작업을 안전히 종료하며 새 게시/예약을 하지 않는다.
- [ ] 실제 검수 ledger adapter, lease heartbeat, SDK 숨은 retry 방지, 동일 count/generate 옵션을 연결한다. 역할별 명시 budget가 없으면 runner 시작을 거절하고 운영 자동 활성화는 하지 않는다.
- [ ] 실제 Spring HTTP + 격리 PostgreSQL + 실제 Python Worker를 합성 provider/S3로 연결한다. 계획 저장 직후 종료 후 정상 복원, 중간 finding 읽기, PARTIAL/FAILED/COMPLETED 0건 구별, 수정/파기·lease 만료 후 늦은 결과 거절, 모든 경로의 추출/후보 0을 검증한다. 테스트 double만 서로 통신하는 검증을 실제 연결로 보고하지 않는다.
- [ ] AI 전체 pytest, 변경 Python Ruff, Backend 전체 ./gradlew test bootJar, 필요한 실제 PG/HTTP 검증과 wheel에 prompt/runtime 포함 검증을 실행한다. skipped/기존 lint와 실제 모델 품질 미측정을 문서로 분리한다.
- [ ] 공통 계약 양쪽 동기화, ai logic v0028 복원 정보, 실행/운영 활성화 경계/수동 확인 방법을 기록하고 의미 단위 commit한다. controller의 최종 Astra review와 draft PR 단계로 인계한다.

## Acceptance mapping

Issue 수정·재검수 1~5는 Task 1 및 Task 2의 결과 무효화 연결이 충족한다. 실행·재시도 1~5는 Task 2와 Task 3의 실제 Worker/DB 연결로 충족한다. 모든 테스트는 #310의 추출 0 계약을 따른다. 공유 Task 4 문서의 검토 종료·추출 1건 조건은 #312 범위이므로 이번 완료 기준으로 사용하지 않는다.
