# 후보 검토 목록 읽기 최적화 명세 — GH296 / GH272

## 목표와 범위

캐릭터·세계관 후보 목록 요청에서 사용하지 않는 AnalysisJob의 `automatic_input_state`, `run_base_state`, `state_journal`을 읽지 않는다. 캐릭터 검토 화면이 세계관 개수를 얻으려고 호출하는 `size=1` 세계관 목록을 전용 요약 API로 바꾼다. 기존 목록 응답과 검토 동작은 그대로 유지한다.

기준 BE는 `64eda0e3f37a07a82616bb01733f947a03547c19`, FE는 `36a143391e8f330ac88c910517585a263626d3e0`이다. 작업 브랜치는 두 저장소 모두 `perf/gh-296-candidate-review-summary`다. 이미 승인된 구현을 진행하며 별도 승인 대기 없이 계획→구현→검증한다. 커밋·push·운영 접근·배포는 수행하지 않는다.

그룹 페이지네이션 SQL 재설계, Worker 저장 경로 GH294, 후보 상세/변경 API의 최적화, 후보 JSON/비교 문맥 자체 제거, 새로운 제품 정책은 범위 밖이다. 세계관은 기존 그룹을 만든 뒤 **페이지에 속한 그룹만 DTO로 매핑**하는 제한적 변경은 허용한다. 그룹 키·순서·페이지 경계·총 개수는 바꾸지 않는다.

## 읽기 구조

현재 목록 Repository의 `analysisJob` fetch graph뿐 아니라 Mapper, 후보의 derived predicate, `comparisonBatch.analysisJob`, `CandidateReviewSourceScope.validate/episodeRange/isActionable`에서도 전체 Job이 로딩된다. 모든 경로를 함께 끊는다.

- 완전한 후보 엔티티와 기존 그룹 계산을 유지한다. 목록용 Repository 읽기에는 Job fetch를 넣지 않는다. mutation에서 재사용하는 기존 조회 메서드를 일괄 변경하지 않고 목록 전용 메서드를 둔다.
- 필요한 원본/비교 Job ID를 모아 scalar projection으로 읽고, immutable `CandidateReviewJobMetadata`와 요청 범위의 ID→metadata 맵으로 전달한다. 관리 엔티티를 불완전하게 재조립하거나 reflection으로 `AnalysisJob`을 채우지 않는다. global JSON lazy 설정·OSIV 설정 변경으로 우회하지 않는다.
- 메타데이터는 ID/work/batch/jobType, analysisMode/reviewMode/status/journalStatus/automaticAppliedAt, source 스냅샷, source episode fallback 번호, current-source-version 여부, 최신 설정집 origin 여부, characterComparisonInputHash를 포함한다. projection에는 Job의 세 큰 JSON과 설정집 원문이 없다. 최신 origin은 기존 `createdAt DESC, id DESC`를 그대로 따른다.
- `AnalysisSourceResponse`의 모든 필드, 설정집 기준 시점, 현재 source version 검증의 nullable S3 version/hash/key 비교를 보존한다. `Objects.equals(null, null)` 의미를 SQL `=`로 바꾸지 않는다. 회차 범위는 비지정 시 기존 aggregate, source 지정 시 frozen sourceEpisodeNo→episodeNo fallback, 설정집은 null/null/0이다.
- 목록 Mapper는 전달받은 metadata만 사용한다. 비교 preview의 후보 rawComparison/backendComparisonBefore·비교 batch context, 현재 캐릭터 snapshot/provenance는 기존처럼 보존한다. 비교 batch Job의 hash는 해당 **비교 Job**에서 읽고 origin hash로 대체하지 않는다.
- Job 상태만 사용하는 정책은 작은 순수 read-state 값과 기존 predicate의 overload/delegation으로 공유한다. 읽기용 정책과 변경용 정책이 서로 다른 조건으로 발전하지 않도록 기존 predicate와의 parity 테스트를 둔다. 기존 no-arg predicate와 상세/변경 Mapper entry point는 유지한다.
- 목록과 summary의 source validation은 scalar 전용 경로를 사용한다. 기존 mutation의 `validate/assertActionable/assertCharacter/assertWorld`와 잠금/쓰기 순서는 유지한다. 목록 조회를 위해 origin의 큰 JSON을 읽는 fallback은 금지한다.

## 새 API 계약

`GET /api/v1/works/{workId}/world-setting-candidates/summary`

- operationId: `getWorldSettingCandidateSummary`
- 필수 query: `batchId: UUID`; 선택 query: `sourceAnalysisJobId: UUID`
- reviewStatus/category/operation/page/size를 받지 않는다. 항상 해당 출처 범위 전체의 집계다.
- 응답: `CommonResponse<WorldSettingCandidateSummaryResponse>`.
- service: `WorldSettingCandidateSummaryResponse getWorldSettingCandidateSummary(Long memberId, UUID workId, UUID batchId, UUID sourceAnalysisJobId)`.
- DTO는 기존 `WorldSettingCandidateListResponse`에서 `groups`만 제외한 아래 필드다. JSON 이름·타입·nullability를 그대로 보존한다.

| 필드 | 타입 |
| --- | --- |
| batchId | UUID |
| episodeStartNo, episodeEndNo | nullable Integer |
| episodeCount | long |
| totalCandidateCount, reviewedCandidateCount, pendingCandidateCount | long |
| pendingComparisonCount, processingComparisonCount, activeComparisonJobCount | long |
| failedComparisonCount, tokenInterruptedComparisonCount | long |
| canResumeTokenInterruptedComparisons | boolean |
| recomparisonRequiredCount, conflictCandidateCount | long |
| confirmedCandidateCount, dismissedCandidateCount | long |
| directReviewCandidateCount, processingCandidateCount | long |

동일한 scope에 대한 기존 목록 최상위 필드와 값이 동일해야 한다. `countReviewSummary`의 자동 반영 대기·토큰 재개 가능성 조건 및 source 범위를 재사용하고, activeComparisonJobCount도 기존 origin/batch 분기를 유지한다. 후보/그룹/Job 엔티티를 조회하지 않는다. `/progress`의 기존 세 count와 호출 계약은 변경하지 않는다.

인증·작품 소유권·배치 소속 검증은 기존 목록과 같다. 다른 회원/작품/배치의 source, 숨김 비교 Job, 존재하지 않는 source는 기존 목록과 같은 오류다. source 생략은 EPISODE만, 지정은 그 추출 origin만 포함한다. 오래된 설정집 origin과 보관/변경된 revision은 조회 가능하되 변경 가능으로 승격하지 않는다. 빈 범위는 정상 0 집계다.

## 반드시 유지할 동작

- 캐릭터 기본 PENDING_REVIEW, page size 20, includeLegacyCandidates=false; includeLegacyCandidates=true의 공개 호환 경로도 세 JSON을 읽지 않는다.
- 필터, 캐릭터 이름 정규화/미상 마지막, 세계관 최종 결정·비교 결정 기반 그룹 키, 안정 정렬, evidence episode 번호, 모든 page 메타데이터.
- sourceAnalysisJobId 및 출처 metadata, sourceAnalysisJobStatus/sourceAnalysisJournalStatus, comparison input hash, current snapshot/version, canApply/manualReviewAvailable/automaticApplicationPending/historyOnly.
- AUTOMATIC+미적용+PENDING/RUNNING의 pending 의미, SUCCEEDED+SEALED+자동적용 latch, 수동 CONFIRMED_ONLY 완료 및 자동 준비 실패 보류 규칙. token 실패를 수동 검토 가능으로 바꾸지 않는다.
- 전체 필터/페이지와 무관한 confirmed+dismissed+directReview+processing=total, directReview+processing=pending 의미.
- 단건·그룹 mutation 결과/예외/멱등성/잠금과 Worker 쓰기 경로.

## 프론트 계약

`CharacterSettingReview.worldSummaryQuery`만 생성된 `getWorldSettingCandidateSummaryOptions`로 전환한다. workId/batchId/sourceAnalysisJobId, enabled/retry/캐시 범위, `processingCandidateCount` 우선 및 pendingComparisonCount+processingComparisonCount fallback의 기존 2초 polling을 보존한다. 새 summary 실패 시 기존 상세 목록으로 fallback하지 않는다. 초기 계획의 3초 표기는 실제 기준 코드의 `ACTIVE_COMPARISON_POLL_INTERVAL = 2_000` 확인 후 정정했다.

요약 대기/실패를 0으로 표시하지 않고 마지막 성공값은 배경 갱신 중 유지한다. 전체 개수·탭·진행률·검토 완료·다른 종류 이동 조건은 그대로다. `setting-review-progress.ts`는 summary 타입을 받는다. `invalidateAppliedSettings`와 세계관 변경/재개/결정 복구의 기존 목록 invalidation 경로는 새 summary 캐시도 무효화한다. source A/B와 작품별 캐시는 분리한다. 생성 코드는 로컬 Backend OpenAPI에서 재생성하며 직접 편집하지 않는다.

## 검증과 합격 조건

1. HTTP→Service→Mapper 실제 PostgreSQL 읽기를 새 persistence context에서 검사한다. 두 목록(legacy 포함), source 지정 EPISODE/SETTING_BOOK 및 summary의 SELECT에 세 JSON 컬럼이 없어야 하고 AnalysisJob entity load는 0이어야 한다. summary는 후보 entity load도 0이다. 테스트 fixture 생성 SQL은 측정에서 제외한다.
2. 기존 상세/변경 entry point를 oracle로 한 metadata/predicate parity와 목록 응답 계약 회귀를 검증한다. 유효·오래된·보관된 설정집, 수동/자동·진행/완료/실패/무효화, comparison Job hash, 선택 안 된 그룹, 빈/마지막/범위 밖 페이지를 포함한다.
3. 격리된 같은 fixture에서 before/after 목록 `data` 전체를 비교하고 summary는 before 세계관 목록에서 groups를 뺀 값과 비교한다. envelope timestamp만 제외한다. fixture readback hash/행 개수로 읽기 전후 데이터가 바뀌지 않았음을 확인한다.
4. localhost 전용 PG27432/API29480/FE3440. 합성 목표 30 Job, 캐릭터585개(대기123개/9그룹), 세계관637개(대기62개), Job JSON 약30MiB이며 실제 값/bytes를 manifest에 기록한다. DB/JVM/fixture/artifact를 양쪽 동일 조건으로 유지한다.
5. warmup 뒤 동일 요청 최소30회에 대해 median/p95/error count/응답 bytes를 기록한다. 캐릭터 목록, 세계관 목록, before size=1→after summary, 화면 최초 요청을 구분한다. 실제 SQL/count, 데이터 equality와 artifact hash를 증거로 보존한다. timing 중 SQL debug·빌드·테스트를 실행하지 않는다. OOM을 강제로 재현하지 않고 실패·중단 표본을 숨기지 않는다.
6. BE 전체 test+bootJar, FE lint/typecheck/build/E2E/check:unused/doctor 및 localhost 실제 연동 확인을 수행한다. unrelated baseline 진단과 이번 회귀를 구분한다. 개선 수치는 측정 결과만 보고하고 page-bounded DB 후보 fetch를 달성했다고 주장하지 않는다.
