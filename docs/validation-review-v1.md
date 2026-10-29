# 작가 판단·검토 종료 v1 — GH312 S1·S2

GH310 검수 실행이 저장한 finding에 작가 판단을 저장하고(S1) 검토 종료 receipt와 설정 추출 release를 만든다(S2).
**마지막 판단 저장은 어떤 경우에도 검토 종료나 추출을 시작하지 않는다.** 검토 종료는 `/complete`의 서버 자동 판정 또는
`POST /review-completion` 명시 요청에서만 생기며, 그 전에는 AnalysisJob·후보·journal·확정이 0이다.
회차 전체 확정(후보 적용 gate)은 S3, 이력·배치 요약 마감은 S4 범위다.

## 저장 모델 (V96)

| 테이블 | 의미 |
| --- | --- |
| `validation_judgments` | finding 행 하나(`finding_id` unique)의 현재 판단. `branch`, `check_id`/`check_revision`(TYPO는 null)을 finding에서 복사한다. `judgment`, 판단 CAS `version`(첫 저장 1), `decided_at`(PENDING이면 null) |
| `validation_judgment_history` | 추가 전용 전이 기록. `from_judgment`(첫 판단은 null), `to_judgment`, `reason` = `AUTHOR` 또는 `REVISION_SUPERSEDED` |

- **판단 행이 없으면 PENDING**이다. 행은 첫 작가 판단에서 만든다. 응답의 version은 행이 없을 때 0이다.
- 허용 값: SETTING은 `PENDING / NEEDS_FIX / NOT_CONFLICT`, TYPO는 `PENDING / NEEDS_FIX / KEEP_ORIGINAL`.
  설정의 NOT_CONFLICT와 오탈자의 KEEP_ORIGINAL은 서로 재사용하지 않는다. DB CHECK도 분기별 값을 강제한다.
- `PENDING`으로 되돌리기(검토 필요로 되돌리기)는 허용하며 행을 지우지 않는다.
- `KEEP_ORIGINAL`은 현재 원고의 해당 항목 판단이다. 다른 위치·회차·작품 예외 사전에 적용하지 않는다.
  `NEEDS_FIX`는 원고를 수정하지 않으며 실제 수정은 기존 파일 교체와 명시적 재검수를 사용한다.

## 현재 항목과 revision 변경

현재 항목은 공개 results와 같은 규칙이다. SETTING은 finding의 `checkRevision`이 현재 check revision과 같을 때,
TYPO는 항상 현재 항목이다. 같은 입력 retry의 FOLLOW_UP이 check를 revise하면
`ValidationResultServiceImpl.reviseValidationChecks`가 같은 트랜잭션에서 옛 revision finding의 판단마다
`REVISION_SUPERSEDED`(from=당시 값, to=PENDING) 이력을 남긴다. 옛 판단 행은 stale finding에 그대로 남아 상세의 이력으로만 보이고
현재 판단으로 승계하지 않는다. 새 revision finding은 판단 행이 없으므로 PENDING이다.
TYPO segment는 성공 저장 뒤 같은 payload 재전송이 receipt만 반환하므로 finding과 판단이 그대로 유지된다.

## 공개 API

접두사는 `/api/v1/works/{workId}/episode-validations/{reportId}`이다. 작품 소유자가 아니면 `WORK_NOT_FOUND`(404),
다른 작품의 report는 `VALIDATION_NOT_FOUND`(404), report에 없는 finding은 `VALIDATION_FINDING_NOT_FOUND`(404)다.

| 경로 | 요청/응답 |
| --- | --- |
| `GET /findings?branch=&judgment=&outcome=&page=0&size=20` | `PageResponse<ValidationFindingSummaryResponse>`. 현재 항목만 생성 순서로 반환한다. page는 0부터, size는 1~100(범위 밖 400). `judgment=PENDING`은 행 없음도 포함한다. evidence 행·단계·bundle은 읽지 않는다 |
| `GET /findings/{findingId}` | `ValidationFindingDetailResponse`: 공개 판정 내용(`payload`), 저장 때 검증한 인용(`evidence`, 재검증 없음), 현재 `judgment`, `allowedJudgments`, `judgmentHistory`, SETTING의 `priorRevisions`(이전 revision의 outcome·당시 판단·check history `changeReason`), `stale` |
| `PATCH /findings/{findingId}/judgment` | `ValidationJudgmentRequest{judgment, expectedJudgmentVersion, expectedCheckRevision}` → `ValidationFindingSummaryResponse` |
| `GET /review` | `ValidationReviewSummaryResponse` (S2: `completion` 포함) |
| `POST /review-completion` | S2. `ValidationReviewCompletionRequest{expectedReportVersion, idempotencyKey}` → `ValidationReviewCompletionResponse` |

목록 요약(`summary`)은 payload에서 `impact`(SETTING), `reason`, `replacement`(TYPO), 원문 위치 `spans`만 투영한다.
상세 `payload`는 `reason/basis/impact/impact_reason/target_spans/evidence_refs/disagreements/completion/span/replacement/category`만 허용하고
completion의 `receipt_refs`는 지운다. `evidence_bundle`(고정 설정·catalogue·조회 receipt)은 GH310 공개 계약대로 내보내지 않는다.
TYPO 인용은 segment 단위로 저장되므로 상세는 제안 위치와 겹치는 인용만 보여준다.

## 판단 저장 규칙

Work 잠금 → report refresh → finding/Job 확인 → 판단 쓰기를 한 트랜잭션에서 수행하고 실제 변경이면 `report.advanceVersion()`을 호출한다.
JPA `@Version`은 사용하지 않는다. 검사 순서와 오류는 다음과 같다.

1. 분기에 맞지 않는 값: `VALIDATION_JUDGMENT_INVALID`(400). 알 수 없는 enum은 `REQUEST_INVALID_ARGUMENT`(400).
2. 판단 잠금: `VALIDATION_JUDGMENT_LOCKED`(409). 현재 report이고 `baselineStatus=READY`, `status=WAITING_FOR_ERROR_REVIEW`,
   ValidationJob이 `COMPLETED/PARTIAL/FAILED`이며 검토 종료 receipt가 없을 때만 열린다. Job 없음·QUEUED·RUNNING·CANCELED·검토 종료와 무효 report는 잠긴다.
   따라서 `/retries`가 다시 queue하면 실행이 끝날 때까지 다시 읽기 전용이다.
3. 오래된 항목: `VALIDATION_JUDGMENT_STALE`(409). finding이 현재 revision이 아니거나 `expectedCheckRevision`이 finding의 값과 다르다(TYPO는 null).
4. 판단 CAS: `VALIDATION_JUDGMENT_VERSION_CONFLICT`(409). 행이 없으면 `expectedJudgmentVersion`은 null 또는 0이어야 한다.

현재 값과 같은 값은 200이며 행·이력·판단 version·reportVersion을 바꾸지 않는다(행이 없는 항목의 PENDING 포함).
값이 바뀌면 행 생성 또는 갱신, `AUTHOR` 이력 추가, 판단 version 증가, `decided_at` 설정(PENDING이면 null) 뒤 reportVersion을 한 번 올린다.

## 검토 요약과 `ValidationReviewGate`

`processor.ValidationReviewGate`는 상태를 바꾸지 않는 판정이다. 판단 수는 현재 항목 기준이며 행이 없으면 Pending으로 센다.

- `completionMode`
  - `AUTOMATIC_ELIGIBLE`: Job COMPLETED, 양쪽 현재 finding 0건, mode BEFORE_CONFIRMATION, 현재·READY·WAITING report.
  - `EXPLICIT_REQUIRED`: 같은 report 조건에서 finding이 있고 settingPending/settingNeedsFix/typoPending/typoNeedsFix가 모두 0.
  - `BLOCKED`: 그 밖의 모든 경우. `holdReasons` ⊂ `EXECUTION_NOT_COMPLETED, PENDING_JUDGMENTS, NEEDS_FIX_PENDING, DIAGNOSTIC_ONLY, REPORT_INVALIDATED, BASELINE_NOT_READY`.
  - `COMPLETED`(S2): 현재 report에 검토 종료 receipt가 있다. 퇴역 report의 receipt는 읽지 않는다.
- `canCompleteReview` = `EXPLICIT_REQUIRED` 또는 receipt 없는 `AUTOMATIC_ELIGIBLE`(아래 자동 종료 보류). `allowedActions`의 `COMPLETE_REVIEW`도 같다.
- `judgmentLocked`/`lockReason` ∈ `REPORT_INVALIDATED, BASELINE_NOT_READY, EXECUTION_NOT_STARTED, EXECUTION_IN_PROGRESS, EXECUTION_CANCELED, REVIEW_COMPLETED`.
- `allowedActions`는 화면 안내용이다. `SAVE_JUDGMENT`(잠금 없음+현재 항목 존재), `COMPLETE_REVIEW`, `RETRY`(실행 활성화+유효 report+PARTIAL/FAILED 또는 Job 없는 READY 기준),
  `RECHECK`(현재 포인터가 무효 report). 최신 회차·seed·예산 같은 실제 조건은 각 요청에서 서버가 다시 검증한다.
- DIAGNOSTIC_ONLY report는 판단 저장은 가능하지만 항상 `BLOCKED(DIAGNOSTIC_ONLY)`다. 이미 반영된 회차의 진단은 새 추출을 만들지 않는다.

`ValidationStagingResponse.errorReviewStatus`도 이 판정을 사용한다. 무효 report `INVALIDATED`, 실행 미완료(Job 없음·진행·PARTIAL·FAILED·CANCELED) `NOT_STARTED`,
`AUTOMATIC_ELIGIBLE`/`EXPLICIT_REQUIRED`/`COMPLETED` `READY`, 그 밖의 완료 report(판단 남음·NEEDS_FIX·진단) `PENDING`이다. RETRY 재전송의 저장 snapshot은 당시 값을 유지한다.
`extractionJobId`·`extractionStatus`는 S2 receipt에서 채우고(아래), `candidateStatus`·`candidateGeneration`은 계속 NOT_STARTED/null이다.

## 검토 종료·release (S2)

### 저장 모델 (V97)

`validation_review_completions`는 report당 한 행(`report_id` unique)인 검토 종료 receipt다.

| 컬럼 | 의미 |
| --- | --- |
| `work_id`, `episode_id` | report의 작품·회차. 작품 단위 키 namespace와 파기용 |
| `trigger` | `AUTOMATIC`(/complete 서버 판정) 또는 `EXPLICIT`(작가 요청) |
| `report_version` | 서버가 검토 종료를 판정한 report version. 종료로 올리기 전 값 |
| `idempotency_key`, `request_hash` | EXPLICIT만. `(work_id, idempotency_key)` 부분 unique index, hash는 `{reportId, expectedReportVersion}` |
| `extraction_job_id`, `extraction_job_generation` | 함께 만든 SETTING_EXTRACTION Job. 공개 재시도가 새 Job으로 바꾸면 갱신하고 세대를 1 올린다 |
| `completed_at` | receipt 생성 시각 |

`ValidationReportStatus`(V91 CHECK)에는 값을 추가하지 않는다. 종료 상태는 이 행의 존재로만 표현한다.

### 원자성

receipt와 Job은 **Work 잠금 → report CAS/refresh → 검토 gate → Job 생성 → receipt 저장 → `report.advanceVersion()`** 순서의 한 트랜잭션이다.
Job은 `AnalysisJobService.createReviewReleaseExtractionJob`이 공개 `createAnalysisJobs`와 같은 private 생성 경로로 만든다. 요청은 단일 신규 회차 업로드 기본값
`AnalysisJobCreateRequest(SETTING_EXTRACTION, report.batch, report.episode)`이므로 `reviewMode` 생략=`AUTOMATIC` → `ORDERED_PROVISIONAL`이다.
회원 최초 분석 시각·batch/회차 소속·원문 파기 대기·진행 중/재개 필요 Job·토큰 최소 예약·S0 캡처·미래 이력 검사를 그대로 거치며 **검수 report 차단만 건너뛴다**.
어느 단계든 실패하면 receipt·Job·report version 모두 되돌린다.

### 자동 경로 (`/complete`)

`ValidationResultServiceImpl.completeValidationJob`이 terminal 상태를 정하고 report version을 올린 같은 트랜잭션에서
`completeValidationReviewAutomatically`를 호출한다. 검토 gate가 `AUTOMATIC_ELIGIBLE`(Job COMPLETED, 양쪽 현재 finding 0건, BEFORE_CONFIRMATION, 현재·READY·WAITING)일 때만
receipt(AUTOMATIC, key 없음)와 Job을 만들고 `/complete` 응답(Worker receipt)에 `reviewCompletionId`·`extractionJobId`를 기록한다. 그 밖에는 아무것도 하지 않으며 두 값은 null이다.
같은 키의 `/complete` 재전송은 저장 receipt를 그대로 반환한다.

**자동 종료 보류**: 추출 생성 조건(예: `AI_TOKEN_QUOTA_EXHAUSTED`, 원문 파기 대기, 진행 중 Job)이 `AppException`으로 막히면 첫 트랜잭션 전체를 rollback하고
같은 `/complete`를 receipt 없이 다시 실행해 검사 결과만 저장한다(경고 로그 `자동 검토 종료를 보류했습니다`). 검사 결과가 추출 조건 때문에 유실되지 않게 하기 위함이다.
두 번째 트랜잭션은 V98 `validation_reports.release_hold_code`(그 오류 코드)·`release_hold_at`을 기록하고, 이후 어느 경로든 receipt를 만들면 둘을 비운다.
이 report는 receipt 없는 `AUTOMATIC_ELIGIBLE`로 남는다. `/review`는 `releaseHold{code, at}`과 `holdReasons=[RELEASE_HELD]`를 보여 주지만
`RELEASE_HELD`는 경로를 막지 않으므로 `canCompleteReview=true`·`COMPLETE_REVIEW`를 유지해 작가가 명시 요청으로 같은 판정을 다시 시도한다(#314가 사유를 표시).

### 명시 경로 (`POST /{reportId}/review-completion`)

Work 잠금·소유권(`WORK_NOT_FOUND`) → 같은 `(work, key)` receipt가 있으면 hash가 같을 때 저장 receipt 200, 다르면 `VALIDATION_IDEMPOTENCY_CONFLICT` →
report 없음 `VALIDATION_NOT_FOUND` → 다른 키로 이미 종료 `VALIDATION_REVIEW_ALREADY_COMPLETED` → `expectedReportVersion` 불일치 `VALIDATION_REPORT_VERSION_CONFLICT` →
gate가 `EXPLICIT_REQUIRED`(또는 보류된 `AUTOMATIC_ELIGIBLE`)가 아니면 `VALIDATION_REVIEW_NOT_COMPLETABLE`(context `completionMode`, `holdReasons`) →
현재 원문·기준·작품·회원 scalar 재검증(`VALIDATION_SOURCE_VERSION_CONFLICT`) → Job·receipt(EXPLICIT).
두 경로 모두 Job 생성 직전 `report.batch`가 회차의 현재 원본 업로드 batch인지 다시 확인하고 아니면 `VALIDATION_SOURCE_VERSION_CONFLICT`다(무효화가 동작하면 도달하지 않는 방어 검사). 추출 생성 조건 오류는 그 코드 그대로 409/404이며 아무것도 저장하지 않는다.
DIAGNOSTIC_ONLY·INVALIDATED·HELD_INCOMPLETE·기준 미준비·실행 미완료는 항상 `NOT_COMPLETABLE`이다.

### 추출 차단 술어

다섯 곳 모두 **"회차의 현재(`is_current`) report에 receipt가 없으면 차단"**으로 바꿨다. 퇴역 report와 그 receipt는 판단에 쓰지 않는다.

| 위치 | 규칙 |
| --- | --- |
| `AnalysisJobEligibility.DOMAIN` (claim·DB snapshot 공유) | 직접·target-only 회차의 현재 report에 receipt가 없으면 claim하지 않는다 |
| `AnalysisJobRepository.existsValidationStagingForEpisodes` | 같은 규칙(이름 유지) |
| `AnalysisJobLeaseServiceImpl` 늦은 callback | 같은 규칙 |
| `AnalysisCandidateSourceGuard.assertCurrent` | 같은 규칙. receipt가 있으면 후보 쓰기를 허용(release 회차 수동 확정 제한은 S3) |
| `AnalysisJobServiceImpl` 공개 생성 | `existsCurrentValidationReportForEpisodes`: 현재 report가 있으면 **종료 뒤에도** 차단. release Job은 receipt 서비스만 만든다 |
| `AnalysisJobServiceImpl` 공개 재시도 | 현재 report가 있으면 그 receipt의 `extraction_job_id` Job만 허용. 새 Job이 생기면 같은 트랜잭션에서 연결 Job·세대를 옮긴다. 기본 release는 ORDERED_PROVISIONAL이라 재시도가 같은 Job을 재개하며 세대는 1로 유지된다 |

차단 오류는 계속 `VALIDATION_ERROR_REVIEW_PENDING`(409)이다.

### 재시도·재검수 상호작용

- 검토 종료 뒤 판단 PATCH는 `VALIDATION_JUDGMENT_LOCKED`(`lockReason=REVIEW_COMPLETED`), 같은 입력 `/retries`는 `VALIDATION_REVIEW_ALREADY_COMPLETED`다.
- 재검수는 기존대로 해당 회차의 PENDING/RUNNING 추출·비교 Job(release Job 포함)이 있으면 `VALIDATION_TARGET_INVALID`로 거절한다. release Job이 끝난 뒤의 재검수는 receipt 없는 새 report를 만들어 추출을 다시 막는다.
- 무효화(`ReportInvalidated`)와 퇴역은 receipt를 이력으로 남긴다. gate·술어는 현재 report의 receipt만 읽는다. 진행 중 release Job이 있으면 기존 정책대로 원고 교체를 거절한다.
- `ValidationStagingResponse`: receipt가 있으면 `extractionJobId`를 채우고 `extractionStatus`는 Job PENDING/RUNNING→`PENDING`, SUCCEEDED→`READY`(journal INCOMPLETE/INVALIDATED면 `FAILED`), FAILED/CANCELED→`FAILED`, receipt 없음→`NOT_STARTED`다.

### 파기 순서

작품/회원 파기(`WorkPurgeDataRepository`)는 `validation_review_completions`를 가장 먼저 지운 뒤 판단 이력 → … → `validation_reports`, 이후 `analysis_jobs`를 지운다.
receipt는 report와 analysis Job을 모두 참조하기 때문이다. `ValidationExecutionRepository.purge`(무효화)는 receipt를 지우지 않는다.

### 잠금 순서

release는 검수 계약대로 Work를 먼저 잠근 뒤 공개 생성 경로의 최초 분석 시각 기록(`AnalysisGuideService.markAnalysisStarted`)에서 회원 row를 잠근다(Work → 회원).
공개 `createAnalysisJobs`는 회원 → Work 순서이고 설정집 업로드·재분석은 이미 Work → 회원 순서다. 이 기존 불일치는 S4에서 정리한다.

### 알려진 한계 (S2 시점, S4에서 다룸)

- 배치 목록·원고 목록의 검수 projection은 receipt 뒤에도 `ERROR_REVIEW_PENDING`을 표시한다.
- 같은 원문인 FAILED ordered release Job이 남은 채 다른 이유(기준 변경 등)로 재검수하면, 새 report의 release는 공개 생성 경로의
  `ANALYSIS_ORDERED_JOB_RETRY_REQUIRED`로 막히고 옛 Job 재시도는 receipt 연결이 없어 막힌다(미검증 이론상 경우, GH201의 설정 변경 거절이 대부분 예방한다).

## 무효화·파기

`ReportInvalidated`를 소비하는 `ValidationExecutionLifecycle`은 같은 Work 잠금 트랜잭션에서
판단 이력 → 판단 → check history → evidence → finding … 순서로 report 복사본을 지운다(`ValidationExecutionRepository.purge`).
원고 교체·기준 출처 변경·확정 설정 변경·재검수 퇴역이 같은 경계를 사용하며 새 report는 이전 판단을 승계하지 않는다.
작품/회원 파기(`WorkPurgeDataRepository`)는 검토 종료 receipt → 판단 이력·판단 → finding/check 순서로 지운다. 무효화는 receipt를 이력으로 남긴다.

## 검증

H2: `./gradlew test --tests '*ValidationReview*' --tests '*ValidationJudgmentMigrationContractTest' --tests '*ValidationReviewCompletionMigrationContractTest'`.
`ValidationReviewReleaseHoldIntegrationTest`는 테스트 트랜잭션 없이 자동 종료 보류의 rollback을 확인한다.
PostgreSQL(V97·V98 Flyway·JPA validate·동시 명시 요청·보류 rollback과 사유 기록)은 새 loopback `gh310_execution_test`에서만 실행한다.

```bash
GH310_EXECUTION_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:<disposable-port>/gh310_execution_test ./gradlew test --tests '*ValidationReviewCompletionPostgresIntegrationTest'
```
