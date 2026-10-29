# 작가 판단 저장·조회 v1 — GH312 S1

GH310 검수 실행이 저장한 finding에 작가 판단을 저장하고 검토 종료 경로를 계산한다.
**S1은 검토 완료 API와 설정 추출 release를 제공하지 않는다.** 마지막 판단을 저장해도, 검토 요약이
`AUTOMATIC_ELIGIBLE`·`EXPLICIT_REQUIRED`를 반환해도 AnalysisJob·후보·journal·확정은 0이다.
기존 추출 차단(`AnalysisJobEligibility`, `existsValidationStagingForEpisodes`, lease/후보 출처 검사)은 그대로다.
검토 완료와 추출 허가는 S2가 `ValidationReviewGate`를 다시 호출해 구현한다.

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
| `GET /review` | `ValidationReviewSummaryResponse` |

목록 요약(`summary`)은 payload에서 `impact`(SETTING), `reason`, `replacement`(TYPO), 원문 위치 `spans`만 투영한다.
상세 `payload`는 `reason/basis/impact/impact_reason/target_spans/evidence_refs/disagreements/completion/span/replacement/category`만 허용하고
completion의 `receipt_refs`는 지운다. `evidence_bundle`(고정 설정·catalogue·조회 receipt)은 GH310 공개 계약대로 내보내지 않는다.
TYPO 인용은 segment 단위로 저장되므로 상세는 제안 위치와 겹치는 인용만 보여준다.

## 판단 저장 규칙

Work 잠금 → report refresh → finding/Job 확인 → 판단 쓰기를 한 트랜잭션에서 수행하고 실제 변경이면 `report.advanceVersion()`을 호출한다.
JPA `@Version`은 사용하지 않는다. 검사 순서와 오류는 다음과 같다.

1. 분기에 맞지 않는 값: `VALIDATION_JUDGMENT_INVALID`(400). 알 수 없는 enum은 `REQUEST_INVALID_ARGUMENT`(400).
2. 판단 잠금: `VALIDATION_JUDGMENT_LOCKED`(409). 현재 report이고 `baselineStatus=READY`, `status=WAITING_FOR_ERROR_REVIEW`,
   ValidationJob이 `COMPLETED/PARTIAL/FAILED`일 때만 열린다. Job 없음·QUEUED·RUNNING·CANCELED와 무효 report는 잠긴다.
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
- `canCompleteReview` = `EXPLICIT_REQUIRED`. `allowedActions`의 `COMPLETE_REVIEW`도 이때만 포함하지만 S1에는 그 endpoint가 없다.
- `judgmentLocked`/`lockReason` ∈ `REPORT_INVALIDATED, BASELINE_NOT_READY, EXECUTION_NOT_STARTED, EXECUTION_IN_PROGRESS, EXECUTION_CANCELED`.
- `allowedActions`는 화면 안내용이다. `SAVE_JUDGMENT`(잠금 없음+현재 항목 존재), `COMPLETE_REVIEW`, `RETRY`(실행 활성화+유효 report+PARTIAL/FAILED 또는 Job 없는 READY 기준),
  `RECHECK`(현재 포인터가 무효 report). 최신 회차·seed·예산 같은 실제 조건은 각 요청에서 서버가 다시 검증한다.
- DIAGNOSTIC_ONLY report는 판단 저장은 가능하지만 항상 `BLOCKED(DIAGNOSTIC_ONLY)`다. 이미 반영된 회차의 진단은 새 추출을 만들지 않는다.

`ValidationStagingResponse.errorReviewStatus`도 이 판정을 사용한다. 무효 report `INVALIDATED`, 실행 미완료(Job 없음·진행·PARTIAL·FAILED·CANCELED) `NOT_STARTED`,
`AUTOMATIC_ELIGIBLE`/`EXPLICIT_REQUIRED` `READY`, 그 밖의 완료 report(판단 남음·NEEDS_FIX·진단) `PENDING`이다. RETRY 재전송의 저장 snapshot은 당시 값을 유지한다.
`extractionStatus`·`candidateStatus`는 계속 NOT_STARTED다.

## 무효화·파기

`ReportInvalidated`를 소비하는 `ValidationExecutionLifecycle`은 같은 Work 잠금 트랜잭션에서
판단 이력 → 판단 → check history → evidence → finding … 순서로 report 복사본을 지운다(`ValidationExecutionRepository.purge`).
원고 교체·기준 출처 변경·확정 설정 변경·재검수 퇴역이 같은 경계를 사용하며 새 report는 이전 판단을 승계하지 않는다.
작품/회원 파기(`WorkPurgeDataRepository`)도 판단 이력·판단을 finding/check보다 먼저 지운다.

## 검증

H2: `./gradlew test --tests '*ValidationReview*' --tests '*ValidationJudgmentMigrationContractTest'`.
V96의 PostgreSQL 적용과 JPA validate는 기존 GH310 loopback PG 테스트 절차에서 별도로 확인해야 한다.
