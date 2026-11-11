# 첫 분석 완료 보고 (#426)

GA4 전환 이벤트 `first_analysis_complete`를 계정당 한 번만 보내기 위한 서버 계약이다. 이벤트는 브라우저가 보내고, 서버는 "보낼 차례인지"와 "어느 요청이 보낼지"만 정한다.

## 서버가 판정하는 이유

- 분석은 Worker에서 비동기로 끝나므로 작가가 그 순간 화면에 없을 수 있다.
- Front는 지금 끝난 분석이 그 계정의 첫 분석인지 알지 못한다. 브라우저 저장소로 추정하면 기기를 바꾸거나 저장소를 지울 때 중복·누락된다.

## 완료 기준

남아 있는 분석 Job 중 아래 조건을 만족하는 설정 추출(`SETTING_EXTRACTION`, 회차·설정집 모두)이 하나라도 있으면 첫 분석을 완료한 것으로 본다.

| 분석 방식 | 완료 조건 |
| --- | --- |
| 단일(`CONFIRMED_ONLY`) | `status = SUCCEEDED` |
| 순차(`ORDERED_PROVISIONAL`) | `SUCCEEDED` + journal `SEALED` + (직접 검토 `MANUAL` 또는 `automatic_applied_at` 또는 `review_application_held_at`) |

화면의 분석 완료 판정(`AnalysisJob.isCompletedOrderedAnalysis`, Front `ordered-analysis.ts`·업로드 화면)과 같다. `SUCCEEDED + INCOMPLETE`는 재개 대기라 완료가 아니다. 오류 리포트 검수(legacy `EPISODE_VALIDATION`과 `validation_jobs`)와 내부 비교·관계 Job은 세지 않는다. 조건을 바꾸면 `AnalysisJobRepository.existsCompletedSettingExtractionByMemberId`, V123 백필, Front 완료 판정을 함께 맞춘다.

## 저장

`members.first_analysis_completion_reported_at`(nullable timestamp)은 보고 선점 시각이며 한 번 기록하면 바꾸지 않는다. 완료 시각 자체는 저장하지 않고 조회 때 Job으로 판정한다.

Worker 완료 경로(`AnalysisJobWorkerServiceImpl.completeAnalysisJob`)에서 기록하지 않는 이유는 잠금 순서다. 완료 트랜잭션은 Work → Job 순서로 잠그고, 분석 생성(`AnalysisJobServiceImpl.createJobs`)은 회원 → Work 순서로 잠근다. 완료 트랜잭션이 회원 행까지 쓰면 같은 회원의 분석 생성과 서로를 기다릴 수 있다.

## API

| 요청 | operationId | 동작 |
| --- | --- | --- |
| `GET /api/v1/first-analysis-completion-reports` | `getMyFirstAnalysisCompletionReport` | 읽기 전용. `shouldReport` = 보고 기록 없음 + 완료 Job 있음 |
| `POST /api/v1/first-analysis-completion-reports/claim` | `claimMyFirstAnalysisCompletionReport` | 회원 행 잠금 → 자격 재검사 → true면 시각 기록. 재요청·동시 요청은 false |

`true`를 받은 claim 요청만 이벤트를 보낸다. 잠그는 행은 회원 하나이며 Job은 잠그지 않고 읽는다. 비활성 회원은 기존 `MEMBER_INACTIVE`로 거절한다.

## 이관 (V123)

배포 전에 이미 첫 설정 추출을 완료한 회원은 남아 있는 Job의 가장 이른 완료 시각(`completed_at`, 없으면 `updated_at`)으로 채워 보고 대상에서 뺀다. 배포 직후 과거의 첫 분석이 GA4에 한꺼번에 기록되지 않게 하기 위함이다. 작품 삭제로 Job이 이미 지워진 회원은 이력을 복원할 수 없어, 다음 분석 완료 때 한 번 보고될 수 있다.

## 수명

작품 삭제는 회원 행을 건드리지 않으므로 기록이 남는다. 회원 탈퇴는 회원 행과 함께 지운다.

## Front 계약

- 인증된 화면에 들어올 때와, 업로드·설정집 진행 화면에서 분석 완료를 볼 때 GET을 확인한다. `true`이면 claim하고, claim이 `true`일 때만 `first_analysis_complete`를 매개변수 없이 보낸다.
- GA4(`window.gtag`)가 없는 환경에서는 claim하지 않는다. Vercel Preview는 운영 API를 쓰지만 GA4가 없으므로, 그곳에서 선점하면 이벤트 없이 기록만 소비되기 때문이다.
- 배포는 Backend를 먼저 하고 Front를 배포한다.
