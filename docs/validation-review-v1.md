# 작가 판단·검토 종료·회차 전체 확정 v1 — GH312 S1·S2·S3·S4

GH310 검수 실행이 저장한 finding에 작가 판단을 저장하고(S1) 검토 종료 receipt와 설정 추출 release를 만든 뒤(S2)
추출 결과를 회차 단위로 한 번에 확정한다(S3). S4는 회차 검수 이력 API와 목록·요약이 함께 쓰는 파생 검수 단계(`reviewStage`)를 더한다.
**마지막 판단 저장은 어떤 경우에도 검토 종료나 추출을 시작하지 않는다.** 검토 종료는 `/complete`의 서버 자동 판정 또는
`POST /review-completion` 명시 요청에서만 생기며, 그 전에는 AnalysisJob·후보·journal·확정이 0이다.
추출 성공은 설정 확정이 아니다. 회차 전체 확정(아래 S3)만 release 회차의 확정 설정을 쓴다.
#314 B1은 접수 전 자격 조회와 미완료 검수 동안의 다음 회차 등록 차단을 더한다(아래 "접수 자격 조회·다음 회차 차단 (#314 B1)").

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
| `POST /settings-application` | S3. `ValidationSettingsApplicationRequest{expectedReportVersion, idempotencyKey}` → `ValidationSettingsApplicationResponse` |

S4의 회차 이력은 report가 아니라 회차 기준이라 접두사 없이 `GET /api/v1/works/{workId}/episode-validations/history?episodeId=&page=0&size=20`(`listEpisodeValidationHistory`)이다(아래 S4).
B1의 접수 자격 조회도 같은 이유로 `GET /api/v1/works/{workId}/episode-validations/eligibility?episodeId=`(`getEpisodeValidationEligibility`)이다.
공개 요청·응답 예시와 단계 계산 예시는 `docs/contracts/validation-review-v1.json`이고 `ValidationReviewContractTest`가 DTO와 왕복을 검사한다.

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
  `RECHECK`(현재 포인터가 무효 report), `APPLY_SETTINGS`(S3: 검토 종료 receipt가 있고 무효 report가 아니며 회차 전체 확정이 APPLIED가 아님).
  최신 회차·seed·예산·추출 진행 같은 실제 조건은 각 요청에서 서버가 다시 검증한다.
- DIAGNOSTIC_ONLY report는 판단 저장은 가능하지만 항상 `BLOCKED(DIAGNOSTIC_ONLY)`다. 이미 반영된 회차의 진단은 새 추출을 만들지 않는다.

`ValidationStagingResponse.errorReviewStatus`도 이 판정을 사용한다. 무효 report `INVALIDATED`, 실행 미완료(Job 없음·진행·PARTIAL·FAILED·CANCELED) `NOT_STARTED`,
`AUTOMATIC_ELIGIBLE`/`EXPLICIT_REQUIRED`/`COMPLETED` `READY`, 그 밖의 완료 report(판단 남음·NEEDS_FIX·진단) `PENDING`이다. RETRY 재전송의 저장 snapshot은 당시 값을 유지한다.
`extractionJobId`·`extractionStatus`는 S2 receipt에서 채우고(아래), `candidateStatus`는 S3 회차 전체 확정 결과(아래)다. `candidateGeneration`은 계속 null이다.

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
| `AnalysisCandidateSourceGuard.assertCurrent` | 같은 규칙. receipt가 있으면 후보 쓰기를 허용. 사람의 확정 경로는 S3의 `assertManualWriteAllowed`가 release 회차를 막는다 |
| `AnalysisJobServiceImpl` 공개 생성 | `existsCurrentValidationReportForEpisodes`: 현재 report가 있으면 **종료 뒤에도** 차단. release Job은 receipt 서비스만 만든다 |
| `AnalysisJobServiceImpl` 공개 재시도 | 현재 report가 있으면 그 receipt의 `extraction_job_id` Job만 허용. 새 Job이 생기면 같은 트랜잭션에서 연결 Job·세대를 옮긴다. 기본 release는 ORDERED_PROVISIONAL이라 재시도가 같은 Job을 재개하며 세대는 1로 유지된다 |

차단 오류는 계속 `VALIDATION_ERROR_REVIEW_PENDING`(409)이다.

### 재시도·재검수 상호작용

- 검토 종료 뒤 판단 PATCH는 `VALIDATION_JUDGMENT_LOCKED`(`lockReason=REVIEW_COMPLETED`), 같은 입력 `/retries`는 `VALIDATION_REVIEW_ALREADY_COMPLETED`다.
- 재검수는 기존대로 해당 회차의 PENDING/RUNNING 추출·비교 Job(release Job 포함)이 있으면 `VALIDATION_TARGET_INVALID`로 거절한다. release Job이 끝난 뒤의 재검수는 receipt 없는 새 report를 만들어 추출을 다시 막는다.
- 무효화(`ReportInvalidated`)와 퇴역은 receipt를 이력으로 남긴다. gate·술어는 현재 report의 receipt만 읽는다. 진행 중 release Job이 있으면 기존 정책대로 원고 교체를 거절한다.
- `ValidationStagingResponse`: receipt가 있으면 `extractionJobId`를 채우고 `extractionStatus`는 Job PENDING/RUNNING→`PENDING`, SUCCEEDED→`READY`(journal INCOMPLETE/INVALIDATED면 `FAILED`), FAILED/CANCELED→`FAILED`, receipt 없음→`NOT_STARTED`다.

### 파기 순서

작품/회원 파기(`WorkPurgeDataRepository`)는 S3 `validation_settings_applications` → `validation_review_completions`를 가장 먼저 지운 뒤 판단 이력 → … → `validation_reports`, 이후 `analysis_jobs`를 지운다.
두 receipt가 report와 analysis Job을, 회차 전체 확정 receipt는 검토 종료 receipt도 참조하기 때문이다. `ValidationExecutionRepository.purge`(무효화)는 두 receipt를 지우지 않는다.

### 잠금 순서

release는 검수 계약대로 Work를 먼저 잠근 뒤 공개 생성 경로의 최초 분석 시각 기록(`AnalysisGuideService.markAnalysisStarted`)에서 회원 row를 잠근다(Work → 회원).
공개 `createAnalysisJobs`는 회원 → Work 순서이고 설정집 업로드·재분석은 이미 Work → 회원 순서다. 같은 회원·작품에서 두 경로가 겹치면 PostgreSQL이 교착을 감지해 한쪽을 되돌릴 수 있다.
S4는 공개 생성 순서를 바꾸지 않았고 결정적으로 재현하는 PostgreSQL 테스트도 두지 않았다. 대신 `domain.validation`의 새 코드는 Work를 먼저 잠그도록 AGENTS.md에 적었다(아래 알려진 한계).

## 회차 전체 확정 (S3)

release 회차(현재 report에 검토 종료 receipt)의 캐릭터·세계관 후보는 `ValidationSettingsApplicationService`만 확정한다.
**Work 잠금 → (명시) report CAS → 평가 → 두 도메인을 한 트랜잭션에서 반영하거나, 하나라도 반영할 수 없으면 아무것도 쓰지 않고 보류를 기록한다.**
부분 성공은 없다. `ValidationReportStatus`에는 값을 추가하지 않고 V99 receipt 행으로 상태를 표현한다.

### 저장 모델 (V99)

`validation_settings_applications`는 report당 한 행(`report_id` unique)이며 평가마다 갱신한다.

| 컬럼 | 의미 |
| --- | --- |
| `completion_id`, `extraction_job_id`, `extraction_job_generation` | 평가한 검토 종료 receipt와 그 receipt의 현재 release Job·세대 |
| `status` | `HELD_REVIEW`(이번 시도는 확정 설정 쓰기 0) 또는 `APPLIED`(두 도메인 모두 성공). `FAILED`는 CHECK에만 예약했고 S3는 쓰지 않는다 |
| `hold_reasons` | HELD_REVIEW만. `[{code, candidateType(CHARACTER/WORLD_SETTING/EPISODE), candidateId?, detail?}]` |
| `attempt` | receipt를 기록한 평가 수. 같은 키 재전송·APPLIED 뒤 요청은 늘리지 않는다 |
| `trigger`, `idempotency_key`, `request_hash` | 마지막 기록 경로. EXPLICIT만 키·`{reportId, expectedReportVersion}` hash를 가진다(`(work_id, idempotency_key)` 부분 unique). AUTOMATIC 시도는 비운다 |
| `last_error_code` | 반영 트랜잭션을 되돌린 AppException 코드 또는 `UNEXPECTED_ERROR` |
| `report_version` | 이 시도가 report version을 올린 뒤의 값. 재전송이 같은 응답을 받는다 |
| `applied_at`, `version` | CHECK `(status='APPLIED') = (applied_at IS NOT NULL)`. `version`은 기록마다 1 증가하는 명시적 값(JPA `@Version` 아님) |

### 평가 (`ValidationApplicationGate.evaluate`, 상태 변경 없음)

현재 report와 검토 종료 receipt가 필요하다(없으면 `VALIDATION_APPLICATION_UNAVAILABLE` 409). 결과는 `READY`(반영 순서 계획), `HELD(사유)`, `NOT_READY`다.

1. report 무효(`INVALIDATED`) → `REPORT_INVALIDATED`. report batch가 회차의 현재 원본 업로드 batch가 아니면 `SOURCE_CHANGED`(detail `UPLOAD_BATCH`).
2. 추출 성공은 receipt가 가리키는 Job의 journal `SEALED` + 상태 `RUNNING`(완료 트랜잭션 안) 또는 `SUCCEEDED`다. 그 전의 `PENDING/RUNNING`은 `NOT_READY`(receipt를 쓰지 않음),
   실패·취소·`INCOMPLETE/INVALIDATED`는 `EXTRACTION_NOT_FINISHED`, 원문 버전이 현재 회차와 다르면 `SOURCE_CHANGED`(detail `EXTRACTION_SOURCE`).
3. 그 Job의 검토 대기 후보를 둘로 나눠 판정한다. 제외·확정 후보는 건너뛴다.
   - **작가 결정 그룹**(contributor의 `reviewedDecisionGroups`): 캐릭터는 `reviewedApplicationMode`를 저장한 후보 또는 작가가 연결·등록 이름을 정한 인물 발견이 있는
     공개 그룹 확정 단위(group key: 연결 인물 ID 또는 정규화 이름), 세계관은 최종 결정 초안(`final*`)이 있는 후보의 분류·대상 그룹과 같은 비교 결정의 원본 후보다.
     결정 있는 후보는 비교 진행 중(`COMPARISON_PENDING`), 캐릭터 값 형식 오류(`VALUE_INVALID`), 모호·미상 대상(`SUBJECT_UNRESOLVED`), 재비교가 필요한 실패(`COMPARISON_FAILED`)만 보류한다.
     같은 그룹의 결정 없는 후보는 AI 제안 그대로(APPLY_PROPOSAL) 확정되므로 아래 자동 판정을 그대로 요구한다.
   - **나머지**: 기존 자동 반영 규칙(`SettingCandidateServiceImpl.applyAutomatically`, `WorldSettingCandidateServiceImpl.isAutomaticCandidate`)과 같은 조건이다.
   - 결정 없는 후보의 검토 필요·비교 실패·값 형식 오류·대상/범위 미정·반영 방식 없는 직접 수정은 `DECISION_PENDING`(detail=원래 코드[:detail])이다.
     진행 중 비교·사용량 중단·재비교가 필요한 캐릭터 실패는 결정으로 풀 수 없으므로 원래 코드를 유지한다.

| 코드 | 감지 조건 |
| --- | --- |
| `DECISION_PENDING` | 위 결정 가능한 사유가 있는데 작가의 저장된 결정이 없다. 캐릭터 반영 방식 저장(PATCH)·세계관 결정 초안 저장(PATCH /decisions) 뒤 다시 반영하면 그 결정대로 확정한다 |
| `SUBJECT_UNRESOLVED` | 미상·AMBIGUOUS·실제/임시 대상 없음·연결 대기·이미 등록된 이름의 미연결 발견, 세계관 주체 해소 AMBIGUOUS/FAILED 또는 `SUBJECT_UNRESOLVED` |
| `SCOPE_UNRESOLVED` | 세계관 `SCOPE_UNRESOLVED`/`SCOPE_MISMATCH` |
| `VALUE_INVALID` | 조회의 `valueValidation INVALID`와 같은 판정(schema 해석 실패 포함). detail은 CharacterErrorCode |
| `COMPARISON_PENDING` / `COMPARISON_FAILED` / `TOKEN_QUOTA` | 비교 PENDING·PROCESSING / FAILED·RECOMPARISON_REQUIRED·NOT_REQUIRED / `AI_TOKEN_QUOTA_EXHAUSTED` 실패 |
| `REVIEW_REQUIRED` | 제안 REVIEW_REQUIRED·없음, 세계관 CONFLICT·`BATCH_LIMIT_EXCEEDED`·`GENERAL_UNCERTAINTY`·결정 불일치·root 이동 비활성, 비교 단계에서 기록된 `automaticReviewHoldReason`(detail) |
| `CANDIDATE_CHANGED` | 반영 단계에서 기존 규칙이 계획한 후보를 보류·미반영함(현재 설정 변경·의존·보호 등, detail은 원래 보류 사유 또는 `NOT_APPLIED`) |
| `UNKNOWN_ERROR` | 반영 트랜잭션이 예외로 되돌려짐. detail과 `last_error_code`가 오류 코드 |

### 반영

READY면 같은 트랜잭션에서 receipt를 먼저 APPLIED로 기록(`report.advanceVersion()`, flush)한 뒤 캐릭터 → 세계관 순서로 도메인마다
① 작가 결정 그룹을 `applyReviewedDecisions`로 반영하고 ② 나머지를 기존 contributor의 `applyAutomatically`로 반영한다.
①은 저장 상태에서 화면의 그룹 확정과 같은 요청(저장한 반영 방식 또는 APPLY_PROPOSAL, 직접 수정 값 반영, 후보 updatedAt / 세계관 `final*`과 conflictResolved=true, 결정 없는 같은 그룹 후보는 AI 제안)을 만들어
공개 그룹 확정에서 떼어낸 내부 본문(`confirmSettingCandidateGroupCore`, 세계관 `confirmCandidateGroupCore(..., releaseApplication)`)을 호출한다. 공개 메서드는 Work 잠금·출처 검증 뒤 같은 본문을
`manualConfirmation=true`로 호출하며 동작은 그대로다. 내부 호출은 release 회차의 사람 확정 차단 하나만 건너뛴다. 확정이 group key를 바꿀 수 있어 그룹은 매번 다시 묶는다.
①에서 확정된 후보는 검토 대기가 아니므로 ②가 다시 반영하거나 보류하지 않는다. contributor와 그 아래 확정 내부(Fact·snapshot·provenance, 신규 인물,
세계관 그룹 확정 core, 이미지 갱신, 서사 시점 확정)는 변경 없이 재사용한다.
contributor는 검증 실패를 보류 표시로 흡수하므로, 실행 뒤 그 Job에 검토 대기 후보가 하나라도 남거나 어떤 예외(그룹 확정의 재비교 필요 포함)라도 나면
`VALIDATION_APPLICATION_ROLLED_BACK` 신호로 트랜잭션 전체(APPLIED receipt·두 도메인 쓰기·보류 표시 포함)를 되돌린다.
남은 후보가 없을 때만 `AnalysisJob.completeReviewReleaseApplication()`이 `automaticAppliedAt`을 기록하고 보류 표지를 비운다.
보류는 확정 설정을 쓰지 않고 receipt(HELD_REVIEW, attempt+1)와 `report.advanceVersion()`만 기록하며, 추출이 SEALED면 Job에 `review_application_held_at`(V99)을 남긴다.
후보에는 보류 표시를 남기지 않아 해소 뒤 재평가가 같은 규칙을 다시 적용한다.

- **APPLIED 표지**: job `automaticAppliedAt`(이미 SEALED), 반영 후보 CONFIRMED/자동 EXCLUDE의 DISMISSED와 `reviewedAutomatically`, receipt APPLIED·`applied_at`.
  `automaticApplicationPending`은 Job이 SUCCEEDED가 된 뒤 파생값으로 false다.
- **보류 상태**: Job은 `SUCCEEDED + SEALED`, `automaticAppliedAt` null, `review_application_held_at` 있음이다. `AnalysisJob.isCompletedOrderedAnalysis()`와
  `AnalysisReviewState.completedOrdered()`(`automaticStageFinished`)가 이 표지를 자동 단계 종료로 읽으므로 후보의 `manualReviewAvailable`, 반영 방식 저장, 세계관 결정 초안 저장이
  자동 반영이 끝난 회차와 같게 열린다. 확정은 여전히 막혀 있고(아래) 저장한 결정은 회차 전체 확정이 반영한다. 후보는 자동 반영 대기가 아니므로 직접 확인 수에 포함된다.
  `automaticAppliedAt`은 재검수의 DIAGNOSTIC_ONLY 근거이므로 APPLIED 전에는 기록하지 않는다. SEALED는 추출 기록 완료 표지라 보류에도 유지한다.
- **자기 반영으로 무효화하지 않음**: 확정 설정 변경의 `ConfirmedSettingsChanged`는 기존처럼 작품의 report를 무효화하지만 `ValidationBaselineService`가 APPLIED receipt가 있는
  report를 건너뛴다. APPLIED는 도메인 쓰기보다 먼저 같은 트랜잭션에 기록하므로 리스너가 그 행을 읽고, 실패하면 함께 되돌려진다(트랜잭션 표지나 이벤트 payload가 아닌 데이터 기준).
  이 report의 기준은 확정 전 검사에 이미 쓰였고 검토 종료·반영까지 끝난 이력이라 이후 설정 변경으로도 무효화하지 않는다. 같은 작품의 다른 report, 원문·설정집 변경 경로는 기존과 같다.
  따라서 APPLIED 뒤 report는 `WAITING_FOR_ERROR_REVIEW`·COMPLETED로 남고 남은 행동이 없다.

### 자동 경로 (추출 완료)

추출 완료 트랜잭션 안에서는 Job이 아직 RUNNING이라 공개 그룹 확정의 늦은 검토 경로를 탈 수 없다. 이때 작가 결정 그룹이 있으면(정상 흐름에서는 추출 중 후보 변경이 막혀 없다)
`CANDIDATE_CHANGED`(detail `SAVED_DECISION_NEEDS_EXPLICIT_APPLICATION`)로 보류하고 명시 반영이 처리한다.


`AnalysisJobWorkerServiceImpl.completeAnalysisJob`이 seal 직후 `isReleaseJob`(현재 report의 receipt가 이 Job을 가리킴)이면 두 contributor 대신 `applyAutomatically`를 호출한다.
일반 회차(report 없음·legacy·설정집)는 기존 contributor 경로 그대로다. 반영이 되돌려지면 S2의 자동 종료 보류처럼 첫 트랜잭션을 전부 되돌리고
같은 완료를 반영 없이 다시 실행해 추출 완료(SUCCEEDED+SEALED, Episode ANALYZED)와 보류 receipt만 저장한다(경고 로그 `회차 전체 확정을 되돌리고`).
비교 실패가 치명적이라 journal이 INCOMPLETE가 되는 기존 분기는 receipt를 만들지 않는다. 바깥 트랜잭션에 참여한 호출은 재실행하지 않고 신호를 그대로 전달한다.

### 명시 경로 (`POST /{reportId}/settings-application`)

Work 잠금·소유권(`WORK_NOT_FOUND`) → 같은 `(work, key)` receipt면 hash가 같을 때 저장 응답 200, 다르면 `VALIDATION_IDEMPOTENCY_CONFLICT` →
report 없음 `VALIDATION_NOT_FOUND` → 현재 report의 검토 종료 receipt 없음 `VALIDATION_APPLICATION_UNAVAILABLE` → 이미 APPLIED면 키·version과 무관하게 같은 APPLIED receipt(시도 증가 없음) →
`expectedReportVersion` 불일치 `VALIDATION_REPORT_VERSION_CONFLICT` → 평가: `NOT_READY` 409 `VALIDATION_APPLICATION_NOT_READY`(receipt 없음), HELD 200(보류 receipt), READY 반영 200.
반영이 되돌려지면 새 트랜잭션에서 같은 검사를 거쳐 그 사유로 HELD_REVIEW를 기록하고 200으로 반환한다. 키 열은 마지막 명시 요청 하나만 보관하므로
이전 시도의 키는 재전송해도 새 평가로 처리된다(APPLIED 뒤에는 같은 APPLIED receipt). 동시 요청은 Work 잠금으로 직렬화되어 APPLIED는 하나다.

### 수동 확정 차단

`AnalysisCandidateSourceGuard.assertManualWriteAllowed`는 대상 회차의 현재 report에 검토 종료 receipt가 있으면 `VALIDATION_RELEASE_MANUAL_CONFIRM_BLOCKED`(409)다.
캐릭터 개별 확정·그룹 확정(최종 결과 승인·신규 인물 포함), 승격 서비스 `promote`/`promoteNewCharacterGroup`,
세계관 개별 확정·사람의 그룹 확정(`automatic=false`)이 후보를 읽은 직후 호출한다. 확정 재전송도 막힌다.
값 수정·반영 방식 저장 PATCH(`reviewedApplicationMode`), 캐릭터 연결, 세계관 결정 초안, 제외·재비교·재개는 확정 설정을 쓰지 않는 결정 저장이라 막지 않으며,
저장한 결정은 다음 회차 전체 확정이 그룹 확정과 같은 규칙으로 반영한다.

### 조회

- `/review`: `application{status, attempt, holdReasons, appliedAt, extractionJobGeneration}`(시도 전·퇴역 report null), `extractionState`(receipt 없음 NOT_STARTED,
  release Job PENDING/RUNNING → RUNNING, SEALED 성공 SUCCEEDED, 실패·취소·INCOMPLETE/INVALIDATED FAILED). `completionMode`는 그대로다.
- `ValidationStagingResponse.candidateStatus`: APPLIED → READY, HELD_REVIEW → PENDING. receipt가 없으면 검토 종료 전·release Job 대기(PENDING) NOT_STARTED,
  실행 중이거나 SEALED 성공 뒤 평가 전 PENDING, 추출 실패 FAILED.

### 재검수

APPLIED는 `automaticAppliedAt`을 남기므로 `ValidationRecheckServiceImpl`의 기존 판정 그대로 다음 재검수가 `DIAGNOSTIC_ONLY`이고
`previousApplicationReceipt.kind=AUTOMATIC_APPLICATION`(release Job)이다. APPLIED report는 무효화되지 않으므로 같은 원고의 `VALIDATION_RECHECK_INPUT_UNCHANGED` 검사에서만
APPLIED receipt를 입력 변경으로 본다(확정이 기준 입력을 바꿨다). 진단 report는 검토 종료가 항상 `NOT_COMPLETABLE`이라 S2 release·S3 반영이 없다.
보류 회차는 확정 이력이 없으므로 재검수가 BEFORE_CONFIRMATION이다(보류 표지는 `previousApplication`에 쓰지 않는다).

보류 뒤 원고를 교체하면 기존 원문 교체 정리가 이전 release Job의 불변 기록을 INVALIDATED로 만들고 검토 전 후보를 원문과 함께 파기한다(남는 미확정 후보 없음).
재검수 → 정상 0건 자동 검토 종료가 새 batch에 새 release Job을 만들고 그 Job이 APPLIED된다. 퇴역 report의 HELD receipt는 이력으로 남는다
(`heldEpisodeReplacedAndRecheckedAppliesNewRelease`, H2 확인).

### 잠금 순서

자동 경로는 기존 완료 순서(Work → Job → 후보·캐릭터·세계관)다. 명시 경로는 Work → report refresh → contributor의 캐릭터·세계관 잠금 순서이며 release Job 행은
Work 잠금으로 직렬화한다(완료된 Job에는 Worker 쓰기가 없다).

S3 시점의 한계는 아래 "알려진 한계"에서 S4 결과와 함께 정리한다.

## 이력 조회·검수 단계 (S4)

### 회차 검수 이력

`GET /api/v1/works/{workId}/episode-validations/history?episodeId=&page=0&size=20` → `PageResponse<ValidationReportHistoryResponse>`.
작품 소유자가 아니면 `WORK_NOT_FOUND`(404), page는 0부터·size는 1~100(범위 밖 `REQUEST_VALIDATION_FAILED` 400), `episodeId` 누락은 400이다.
report를 `work_id`와 `episode_id`로만 거르므로 다른 작품의 회차 ID나 report가 없는 회차는 빈 페이지다. 최신 revision부터 정렬한다.

| 필드 | 값 |
| --- | --- |
| `reportId`, `revision`, `current`, `mode`, `status`, `baselineStatus`, `previousReportId` | report 행 값 |
| `requestedAt` | report 생성(접수) 시각 |
| `executionStatus` | 전용 ValidationJob 상태. Job이 없으면 `NOT_STARTED` |
| `counts{settingFindings, typoFindings}` | `/review` counts의 total과 같은 현재 revision 규칙. report들을 묶은 count 집계 한 번으로 구하며 항목 행을 읽지 않는다. 파기된 report는 0 |
| `completion{trigger, completedAt}` | 검토 종료 receipt. 없으면 null |
| `application{status, appliedAt}` | 회차 전체 확정 receipt. 시도 전 null |
| `releaseHold{code, at}` | 자동 검토 종료 보류(V98). 없거나 receipt가 생겨 비워졌으면 null |
| `invalidatedAt` | V100 `validation_reports.invalidated_at`. `ValidationReport.purge()`가 처음 INVALIDATED로 바꿀 때만 기록하고 반복 파기는 바꾸지 않는다. 유효한 report와 V100 이전에 무효화된 행은 null(추정해 채우지 않음). DB CHECK는 INVALIDATED에만 값을 허용한다 |
| `reviewStage` | 아래 파생 검수 단계. 퇴역·무효 report는 `INVALIDATED` |

과거 응답은 요약만이다. 인용·근거·판정 payload·판단 목록·고정 기준은 반환하지 않는다. 검토 요약(`/review`)과 추출 허가는 계속 현재 report의 receipt만 읽지만,
이력은 퇴역 report의 두 receipt도 이력으로 보여 준다. `ValidationStagingResponse`에 `historyCount`는 추가하지 않았다(모든 단건 응답과 RETRY snapshot에 count query가 늘고 이력은 페이지로 조회하므로).

### 파생 검수 단계 `reviewStage`

`type.ValidationReviewStage`는 저장하지 않는 파생값이며 `processor.ValidationReviewStages.resolve`만 계산한다. 입력은 report(`current`, `status`, `baselineStatus`),
ValidationJob 상태, 검토 종료 receipt 존재, receipt가 가리키는 release Job의 `status`·`journalStatus`(`ValidationReviewStages.extractionState`, `/review` extractionState와 같은 규칙), 회차 전체 확정 receipt 상태뿐이다.
위에서부터 처음 맞는 행을 쓴다.

| reviewStage | 조건 | 같이 보는 기존 필드 |
| --- | --- | --- |
| `INVALIDATED` | `current=false`, status 또는 baseline INVALIDATED, 실행 CANCELED(파기 무효화에서만 생김) | errorReviewStatus INVALIDATED, 원고 목록 ERROR_REVIEW_INVALIDATED |
| `HELD_INCOMPLETE` | status HELD_INCOMPLETE 또는 baseline FAILED | ERROR_REVIEW_HELD |
| `PREPARING_BASELINE` | 그 밖에 status가 WAITING_FOR_ERROR_REVIEW가 아니거나 baseline이 READY가 아님 | ERROR_REVIEW_PREPARING |
| `APPLIED` | receipt 있음 + 회차 전체 확정 APPLIED | candidateStatus READY |
| `EXTRACTION_FAILED` | receipt 있음 + release Job FAILED/CANCELED, SUCCEEDED+INCOMPLETE/INVALIDATED 또는 Job 행 없음 | extractionStatus FAILED |
| `APPLICATION_HELD` | receipt 있음 + 확정 receipt HELD_REVIEW(예약값 FAILED 포함)이고 release Job이 다시 실행 중이 아님 | candidateStatus PENDING |
| `REVIEW_COMPLETED` | receipt 있음 + 그 밖(추출 PENDING/RUNNING, SEALED 성공 뒤 평가 전, 보류 뒤 공개 재시도로 재실행 중) | extractionStatus PENDING/READY |
| `AWAITING_REVIEW` | receipt 없음 + 실행 COMPLETED/PARTIAL/FAILED. 판단 남음·수정 필요·명시 완료 대기·자동 종료 보류(RELEASE_HELD)·DIAGNOSTIC_ONLY 모두 | errorReviewStatus PENDING/READY/NOT_STARTED, completionMode |
| `VALIDATING` | receipt 없음 + Job 없음·QUEUED·RUNNING | errorReviewStatus NOT_STARTED |

노출 위치는 `ValidationStagingResponse.reviewStage`(단건·현재·접수·재검수·재시도 응답), `ValidationReviewSummaryResponse.reviewStage`, 이력 행,
원고 목록 `EpisodeSummaryResponse.validationReviewStage`(현재 report가 없으면 null), 배치 요약 `validationReports[].reviewStage`다.
단건은 `ValidationReviewGate`, 원고 목록은 `ValidationReportRepository.findStatesByEpisodeIdIn`의 JPQL constructor projection, 배치 요약은 `AnalysisBatchSummaryRepository.readValidations`의 native SQL,
이력은 `ValidationReviewRepository.history`의 JPQL projection이 scalar 값을 읽어 같은 함수를 호출한다. 목록은 행마다 엔티티·원문·finding을 읽지 않는다.
기존 필드는 그대로다. `errorReviewStatus`·`extractionStatus`·`candidateStatus`, 원고 목록 `analysisStatus`(`ERROR_REVIEW_*`), 배치 카드 `status`·`episodeStatus`는 S1~S3 의미(report status 기반)를 유지하며,
검토 종료 뒤 단계 표시는 `reviewStage`를 쓴다(#314).

배치 요약의 release Job 표시: `CURRENT_TARGETS.normalized`는 순차 자동 Job이 SUCCEEDED+SEALED여도 `automatic_applied_at`이 없으면 `FAILED`로 세었다.
이제 V99 `review_application_held_at`이 있는 Job(회차 전체 확정 보류)은 제외해 `SUCCEEDED`로 센다. APPLIED Job은 이미 `automatic_applied_at`이 있어 SUCCEEDED다.
따라서 보류·반영된 release Job의 `episodeJobGroups[SETTING_EXTRACTION]`은 succeeded 1·failed 0·COMPLETED이고, 확정 보류 여부는 같은 카드의 `validationReports[].reviewStage`(APPLICATION_HELD)로 구분한다.
새 배치 enum은 추가하지 않았다. 보류 표지가 없는 일반 순차 자동 Job의 반영 미완료는 기존처럼 FAILED다.

### 재검수와 이전 release Job

재검수(`recheckValidation`)는 이전 report를 퇴역시키기 직전 그 report의 검토 종료 receipt가 가리키는 release Job을 확인한다.
Job이 재개를 기다리는 순차 실패(journal PENDING/INCOMPLETE이면서 FAILED, 또는 SUCCEEDED+INCOMPLETE)이면 기존 순차 무효화 경로 `AnalysisRunStateService.invalidateFrom`(Work → Job 잠금)으로 journal을 INVALIDATED로 닫는다.
닫지 않으면 같은 원문·batch의 새 report release가 공개 생성 경로의 `ANALYSIS_ORDERED_JOB_RETRY_REQUIRED`에 막히고(자동 경로는 release 보류, 명시 경로는 409), 옛 Job의 공개 재시도는
현재 report에 receipt가 없어 `VALIDATION_ERROR_REVIEW_PENDING`이라 진행할 방법이 없었다(S4 테스트로 재현). 진행 중 Job은 기존대로 재검수를 거절하고, 파일 교체 경로는 이미 원문 교체 정리가 같은 Job을 무효화한다.

### 작가가 해볼 시나리오

| 시나리오 | 작가 행동 | 기대 `reviewStage` |
| --- | --- | --- |
| 업로드 → 검사 → 0건 자동 추출 → 확정 | 단일 신규 회차 업로드, 검수 접수, 검사 정상 0건 | VALIDATING → (자동 검토 종료) REVIEW_COMPLETED → 추출 완료와 같은 트랜잭션의 회차 전체 확정 → APPLIED. 확정할 수 없으면 APPLICATION_HELD |
| 항목 검토 → 명시 추출 → 확정 | 항목마다 충돌 아님/원문 유지 저장 뒤 `POST /review-completion` | 판단 중과 모두 판단한 뒤 모두 AWAITING_REVIEW → REVIEW_COMPLETED → APPLIED(또는 APPLICATION_HELD) |
| 수정 필요 → 교체 → 재검수 | 수정 필요 저장, 회차 파일 교체, `POST /rechecks` | AWAITING_REVIEW → (교체) INVALIDATED → 새 report VALIDATING → AWAITING_REVIEW 또는 0건이면 REVIEW_COMPLETED. 이전 report는 이력에서 INVALIDATED·`invalidatedAt` |
| 보류 → 결정 저장 → 명시 반영 | 반영 방식·세계관 결정 초안 저장 또는 허용된 제외 뒤 `POST /settings-application` | APPLICATION_HELD → APPLIED. 다시 보류되면 APPLICATION_HELD(attempt 증가) |
| 확정 뒤 진단 재검수 | APPLIED 회차를 `POST /rechecks` | 이전 report INVALIDATED(이력의 application APPLIED) → 새 report(mode DIAGNOSTIC_ONLY) VALIDATING → AWAITING_REVIEW에서 멈춤. 검토 종료·추출·반영 없음 |
| (참고) 추출 실패 | release Job 실패 | EXTRACTION_FAILED → 공개 재시도(같은 Job) → REVIEW_COMPLETED → APPLIED/APPLICATION_HELD |
| 미완료 중 다음 회차 업로드(#314 B1) | APPLIED 전에 102화를 어떤 방식으로든 업로드 | 409 `VALIDATION_NEXT_EPISODE_BLOCKED`, context의 report로 이동. 원고 교체로 INVALIDATED여도 막히며 재검수 → 새 report가 APPLIED가 된 뒤(또는 확정 뒤 진단 재검수 중)에만 업로드 성공 |

### 알려진 한계 (#314 또는 후속)

S2·S3에서 S4로 넘긴 항목의 결과다.

- 목록의 `ERROR_REVIEW_PENDING`(S2): **해결.** 원고 목록·배치 요약이 `reviewStage`를 함께 반환한다. 하위 호환을 위해 `analysisStatus`와 배치 카드 `status`/`episodeStatus`는 바꾸지 않았으므로 검토 종료 뒤에도 `ERROR_REVIEW_PENDING`이다. 화면은 `reviewStage`를 기준으로 표시한다(#314).
- FAILED 순차 release Job + 재검수(S2): **수정.** 위 "재검수와 이전 release Job". `ValidationReviewStageIntegrationTest.recheckClosesStaleFailedReleaseJob`이 실패 → 기준 변경 신호 → 재검수 → 새 release → APPLIED 전체를 확인한다. 기준 변경은 GH201이 확정 설정 직접 수정을 막으므로 테스트에서 `confirmedSettingsChanged` 신호로 대신했다(설정집 편집처럼 GH201 검사를 거치지 않는 실제 경로는 테스트하지 않음).
- release(Work → 회원)와 공개 생성(회원 → Work) 잠금 순서: **유지(문서화).** 결정적 PostgreSQL 교착 재현 테스트는 두지 않았다. AGENTS.md "오류 검사 우선 검수 정책"에 `domain.validation`은 Work를 먼저 잠근다는 지침을 추가했다.
- 보류 release Job의 배치 FAILED(S3): **수정.** 위 배치 요약 표시. `heldAndAppliedStagesAgreeAcrossProjections`.
- 보류 동안 목록 표시(S3): **해결.** `reviewStage=APPLICATION_HELD`.
- 같은 비교 결정의 원본 후보가 결정 초안으로 다른 분류·대상에 나뉘는 경우(S3): **동작 고정.** 반영은 `WORLD_SETTING_CANDIDATE_GROUP_INVALID`로 전부 되돌려 `UNKNOWN_ERROR`(detail 같은 코드) 보류, 쓰기 0이며 작가가 같은 대상으로 맞추면 APPLIED다(`splitSharedDecisionHoldsAsUnknownErrorUntilRegrouped`). 같은 임시 인물의 별칭 후보가 다른 group key에 있는 캐릭터 경우는 같은 그룹 확정 본문을 거치지만 테스트하지 않았다(미검증).
- Front 계획의 `getEpisodeValidationEligibility`(접수 전 자격 조회): **해결(#314 B1).** 접수와 같은 판정 컴포넌트를 읽는 조회 전용 API다. 접수 자격의 권위는 계속 `POST /episode-validations`다(아래 B1).
- 기존 GH310 endpoint(`/results`, `/retries`, `/rechecks`, `/corrections`)의 OpenAPI 4xx 응답: **해결(#314 B1).** `/results` 404, 나머지 셋 400·404·409를 문서화했고 `EpisodeValidationOpenApiIntegrationTest`가 확인한다.

## 접수 자격 조회·다음 회차 차단 (#314 B1)

### 접수 자격 조회

`GET /api/v1/works/{workId}/episode-validations/eligibility?episodeId=` (`getEpisodeValidationEligibility`) → `ValidationEligibilityResponse`.
조회 전용이다. report·요청 키·ValidationJob·AnalysisJob을 만들지 않고 report version도 바꾸지 않는다. 작품 소유자가 아니면 `WORK_NOT_FOUND`(404), `episodeId` 형식 오류는 400이다.
접수 자격의 최종 권위는 계속 `POST /episode-validations`다. 화면은 업로드·접수 전에 이 응답으로 사유를 먼저 보여 준다.

판정은 `processor.ValidationIntakeEligibility.judge` 하나다. `EpisodeValidationService.start`는 Work 잠금 뒤 같은 결과로 예외를 던지고, 자격 조회는 그 결과를 읽기만 한다(조건 중복 없음).
요청마다 달라지는 값(멱등 키, `batchId`, 원고 hash/key/version)은 접수만 검사한다. 위에서부터 처음 맞는 사유를 쓴다.

| 순서 | `reasonCode` | 조건 | 같은 입력의 접수 결과 |
| --- | --- | --- | --- |
| 1 | `REPORT_EXISTS` | 회차에 같은 작품의 현재 report가 있음 | 새 report 없음. 최초 report(revision 1)의 같은 입력은 새 키로도 같은 식별자, 다른 입력은 `VALIDATION_IDEMPOTENCY_CONFLICT`, 재검수 report면 `VALIDATION_TARGET_INVALID` |
| 2 | `EPISODE_NOT_FOUND` | 작품에 없거나 보관된 회차, 다른 작품의 회차(그 report는 노출하지 않음) | `VALIDATION_TARGET_INVALID`(400) |
| 3 | `NOT_SINGLE_APPEND_UPLOAD` | 원본이 회차 원고·`SINGLE_EPISODE` 1회차 batch가 아니거나 `Episode.validationUploadEligible=false`(마지막+1이 아닌 단일 업로드, 다회차, 교체·직접 수정 원고) | 같음 |
| 4 | `NOT_LATEST_EPISODE` | 작품의 최신 활성 회차가 아님 | 같음 |
| 5 | `SOURCE_PURGE_PENDING` | 회차 원문 파기 요청이 남아 있음 | 같음 |
| 6 | `ANALYSIS_ALREADY_STARTED` | 회차에 일반 분석 Job이 있음(target-only legacy 관계 포함) | 같음 |
| – | (접수만) | `batchId`가 회차 원본 batch와 다르면 `VALIDATION_TARGET_INVALID`, 원고 hash/key/version이 다르면 `VALIDATION_SOURCE_VERSION_CONFLICT`(409) | |
| 7 | `NEXT_EPISODE_BLOCKED` | 작품의 다른 회차에 아래 차단 report가 있음 | `VALIDATION_NEXT_EPISODE_BLOCKED`(409, 업로드와 같은 context) |
| 8 | `COMPARISON_MATERIAL_REQUIRED` | 앞 회차가 없고 활성 인물·세계관·현재 설정집이 없음 | `VALIDATION_COMPARISON_MATERIAL_REQUIRED`(400) |
| 9 | `ELIGIBLE` | 그 밖 | 새 report 접수 |

`message`는 거절 사유마다 접수 오류와 같은 문구다(2~6은 `VALIDATION_TARGET_INVALID` 문구 하나를 공유하므로 화면은 `reasonCode`로 구분한다). `ELIGIBLE`·`REPORT_EXISTS`만 별도 안내 문구다.
`NEXT_EPISODE_BLOCKED`는 B1 이전에 이미 등록된 뒤 회차에서만 생긴다. B1 뒤에는 그런 회차의 업로드 자체가 막힌다. 접수(`start`) 쪽 동작 변경은 이 거절 하나다.

| 필드 | 값 |
| --- | --- |
| `episodeId` | 조회한 회차. 생략하면 null |
| `eligible` | `reasonCode == ELIGIBLE`. `episodeId`를 생략하면 null |
| `reasonCode`, `message` | 위 표. `episodeId`를 생략하면 null |
| `latestEpisode` | 최신 활성 회차인지. 없는 회차는 false, `episodeId`를 생략하면 null |
| `currentReport{reportId, revision, mode, reviewStage}` | 회차의 현재 report(`REPORT_EXISTS`). 없으면 null |
| `nextEpisodeAdmission{allowed, blockingReportId, blockingEpisodeId, blockingEpisodeNo, blockingReviewStage, message}` | 작품 단위 다음 회차 등록 가능 여부. 항상 채운다. 허용이면 blocking 값과 `message`가 null |

`ValidationStagingResponse`에는 `nextEpisodeAdmission`을 추가하지 않았다. 단건·접수·재검수·재시도 응답과 RETRY 저장 snapshot마다 작품 단위 조회가 늘기 때문이며, 화면은 이 조회를 사용한다.

### 다음 회차 등록 차단

작품의 활성(보관되지 않은) 회차에 있는 현재(is_current) report를 기준으로 판정한다. 규칙은 "`BEFORE_CONFIRMATION` 현재 report의 `reviewStage`가 APPLIED가 아니면 차단"이다.

| 검수 report | 다음 회차 등록 |
| --- | --- |
| `mode=BEFORE_CONFIRMATION`이고 `reviewStage` ∈ `PREPARING_BASELINE`, `VALIDATING`, `AWAITING_REVIEW`, `REVIEW_COMPLETED`, `EXTRACTION_FAILED`, `APPLICATION_HELD`, `HELD_INCOMPLETE` | **차단** (409) |
| `mode=BEFORE_CONFIRMATION`이고 `INVALIDATED`(원고 교체·기준 변경·원문 파기 무효화로 current인 채 무효) | **차단** (409). 무효 report는 재검수가 퇴역시킬 때까지 current로 남아 그 회차의 추출을 계속 막는다. 다음 회차를 받으면 그 회차가 최신이 아니어서 재검수가 `VALIDATION_TARGET_INVALID`로 거절되고 v1에는 검수 포기 경로가 없어 추출할 길이 사라진다. 재검수 → 새 report APPLIED 뒤 허용 |
| `mode=BEFORE_CONFIRMATION`이고 `APPLIED` | 허용 |
| `mode=DIAGNOSTIC_ONLY`(확정 뒤 진단 재검수)의 모든 단계 | 허용. 진단은 AWAITING_REVIEW에서 멈추는 설계라 막으면 다음 회차를 영원히 등록할 수 없다 |
| report 없음 | 허용 |

판정에 쓰지 않는 report: 재검수로 퇴역한(`current=false`) report와 삭제(`ARCHIVED`)한 회차의 report다. 회차 삭제는 report를 퇴역시키지 않으므로 보관 회차를 제외하지 않으면 그 작품은 영원히 막힌다.

- 판정은 `processor.ValidationNextEpisodeAdmission` 하나다(`blocks(mode, reviewStage)`). 단계는 `ValidationReportRepository.findAdmissionStatesByWorkId` scalar projection이 `ValidationReviewStages.resolve`로 계산하며 finding·seed·entries를 읽지 않는다. 차단 report가 여럿이면(B1 이전 자료) 가장 앞 회차를 보여 준다.
- 적용 위치는 `EpisodeServiceImpl.uploadEpisodes` 하나로, `SINGLE_EPISODE`·`MULTI_EPISODE_SINGLE_FILE`·`MULTI_EPISODE_MULTI_FILE` 모두 Work 잠금 직후 파싱·`UploadBatch`/`UploadFile`/`Episode`·저장소 쓰기 전에 판정한다. 막히면 DB 쓰기와 저장 객체가 0이다.
- 409 응답은 `VALIDATION_NEXT_EPISODE_BLOCKED`, 문구 `이 회차의 검토와 설정 저장을 마친 뒤 다음 회차를 등록할 수 있어요.`, `error.context{reportId, episodeId, episodeNo, reviewStage}`다. 예시는 `docs/contracts/validation-review-v1.json`의 `errors.nextEpisodeBlocked`.
- 막지 않는 경로: `detectEpisodes`(파싱만, 쓰기 없음), 어느 회차든 `replaceEpisodeFile`(수정 경로. report는 무효화되지만 current로 남아 차단은 유지되고 재검수로 이어진다), 설정집 업로드·편집, 회차 제목·원고 수정, 회차 삭제.

### 잠금 순서

업로드와 접수는 모두 `WorkRepository.getOwnedWorkForUpdate`(Work 행 `PESSIMISTIC_WRITE`)를 가장 먼저 잡고 그 안에서 판정과 쓰기를 한다. 두 판정 컴포넌트는 Work보다 먼저 회원·Job·후보를 잠그지 않는다(회원 → Work 순서 없음).
"N+1 업로드"와 "N 검수 접수"가 겹치면 결과는 둘 중 하나다. 업로드가 먼저 잠그면 N+1이 생기고 N 접수는 `NOT_LATEST_EPISODE`(`VALIDATION_TARGET_INVALID`), 접수가 먼저 잠그면 report가 생기고 업로드는 409·쓰기 0이다.

### 검증

- H2 `ValidationNextEpisodeAdmissionIntegrationTest`: 세 업로드 방식의 AWAITING_REVIEW·APPLICATION_HELD 차단과 쓰기 0(HTTP 409 context 포함), APPLIED 뒤 허용과 새 회차의 검수 자격, 파일 교체로 INVALIDATED인 current report의 차단과 재검수 APPLIED 뒤 허용, DIAGNOSTIC_ONLY·삭제 회차 report 비차단, 감지·제목 수정·설정집 업로드·파일 교체 비차단, 모든 `reasonCode`에서 자격 조회와 같은 입력의 접수 결과 일치, 자격 조회의 쓰기 0.
- PostgreSQL `ValidationNextEpisodeAdmissionPostgresIntegrationTest`(H2 메서드 상속): Work 행을 잠근 채 두 요청을 같은 잠금에 줄 세워 두 순서 모두 한쪽만 성공·교착 없음·부분 쓰기 없음을 확인하고, 커밋된 접수 뒤 세 방식 업로드 409를 확인한다.
- 계약: `ValidationReviewContractTest`(자격 조회 예시·409 예시·차단 규칙 표), `EpisodeValidationOpenApiIntegrationTest`(operationId·schema·4xx).

```bash
GH310_EXECUTION_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:<disposable-port>/gh310_execution_test ./gradlew test --tests '*ValidationNextEpisodeAdmission*'
```

## 무효화·파기

`ReportInvalidated`를 소비하는 `ValidationExecutionLifecycle`은 같은 Work 잠금 트랜잭션에서
판단 이력 → 판단 → check history → evidence → finding … 순서로 report 복사본을 지운다(`ValidationExecutionRepository.purge`).
원고 교체·기준 출처 변경·확정 설정 변경·재검수 퇴역이 같은 경계를 사용하며 새 report는 이전 판단을 승계하지 않는다.
처음 INVALIDATED로 바뀔 때 `ValidationReport.purge()`가 V100 `invalidated_at`을 기록하고(이력 `invalidatedAt`) 반복 파기는 바꾸지 않는다.
작품/회원 파기(`WorkPurgeDataRepository`)는 회차 전체 확정 receipt → 검토 종료 receipt → 판단 이력·판단 → finding/check 순서로 지운다. 무효화는 두 receipt를 이력으로 남긴다.

## 검증

H2: `./gradlew test --tests '*ValidationReview*' --tests '*ValidationSettingsApplication*' --tests '*ValidationHistory*' --tests '*EpisodeValidationOpenApi*' --tests '*ValidationJudgmentMigrationContractTest' --tests '*ValidationReviewCompletionMigrationContractTest' --tests '*ValidationReportInvalidatedAtMigrationContractTest'`.
`ValidationSettingsApplicationIntegrationTest`·`ValidationSettingsApplicationRollbackIntegrationTest`·`ValidationReviewStageIntegrationTest`는 테스트 트랜잭션 없이 release Job을 claim·완료하며
Worker claim이 DB 전체의 대기 Job을 고르므로 메서드마다 새 Context를 쓴다. 세계관 반영 실패는 테스트 spy로만 만든다.
`ValidationReviewReleaseHoldIntegrationTest`는 테스트 트랜잭션 없이 자동 종료 보류의 rollback을 확인한다.
S4: `ValidationReviewStagesTest`(단계 표), `ValidationReviewStageIntegrationTest`(단건·원고 목록·배치 요약·이력의 같은 단계, 보류 Job 배치 집계, 실패 release Job 재검수 전체 경로, 공유 결정 분할 보류),
`ValidationHistoryIntegrationTest`(MockMvc 이력·요약 전용·소유권·페이지), `ValidationReviewContractTest`(공개 fixture 왕복과 단계 예시), `EpisodeValidationOpenApiIntegrationTest`(operationId·schema·4xx·nested schema 이름, 출력은 `build/openapi/`).
PostgreSQL(V97~V100 Flyway·JPA validate·동시 명시 요청·보류 rollback과 사유 기록·S4 projection SQL)은 새 loopback `gh310_execution_test`에서만 실행한다.

```bash
GH310_EXECUTION_POSTGRES_JDBC_URL=jdbc:postgresql://127.0.0.1:<disposable-port>/gh310_execution_test ./gradlew test --tests '*ValidationReviewCompletionPostgresIntegrationTest' --tests '*ValidationSettingsApplicationPostgresIntegrationTest' --tests '*ValidationReviewStagePostgresIntegrationTest'
```

S3 PostgreSQL 테스트는 V99 Flyway·JPA validate, 다른 키의 동시 명시 반영(APPLIED 하나·시도 하나), 세계관 실패의 전체 되돌림을 확인한다.
S4 PostgreSQL 테스트(`ValidationReviewStagePostgresIntegrationTest`)는 H2 클래스의 메서드를 그대로 상속해 원고 목록 JPQL·배치 요약 native SQL·이력 JPQL과 재검수 무효화를 실제 PostgreSQL에서 실행하고,
V100 `invalidated_at`이 처음 무효화 때만 기록되며 유효 report에는 CHECK로 저장할 수 없음을 확인한다.
