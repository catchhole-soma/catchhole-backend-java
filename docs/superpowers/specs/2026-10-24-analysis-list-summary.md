# 분석 목록 카드 요약 조회 설계

작성일: 2026-10-24. 상태: 구현 전 설계. 사용자가 승인한 분석 목록 최적화 범위이며 #272 세계관 후보 검토 페이지네이션은 별도 작업이다.

## 문제와 범위

`nav=analyses`는 배치 요약을 받은 뒤 전체 작품의 분석 작업을 다시 받아 출처와 이동 대상을 판별한다. 부모 `S1Dashboard`도 회차 목록을 조회한다. 배치 API의 후보 수는 이미 DB 집계지만 현재 작업 선택은 `AnalysisJob` 엔티티 전체를 읽어 Java에서 수행하므로 `automatic_input_state`, `run_base_state`, `state_journal`까지 읽는다. 기존 회차 전용 사고 경로가 세계관 후보 목록 API를 호출한다고 설명하지 않는다. 설정집 카드는 별도로 `SettingBookAnalysisStatus`를 마운트하여 작업 상세·설정집 본문 상세·캐릭터 및 세계관 후보를 조회한다.

목표는 현재 페이지의 카드 표시와 이동에 필요한 요약만 가져오는 것이다. 상세는 사용자가 진행·검토·원문을 열 때 기존 화면에서 읽는다. 큰 JSON을 빼고 작업별 20여 컬럼을 모두 Java로 옮겨 기존 집계를 반복하는 것으로 완료하지 않는다.

기준 소스: Backend `0bf469df9c6db274ca12ce6a88f7d10115515899`, Front `49a1112fb1b6a6f9692b403e3d6482c56b25ffba`. 이전 진단 기준 5df3a8 이후 3개 커밋은 Discord 실패 알림 추가이며 목록 조회·집계 경로에는 변경이 없다. 새 기준의 V88 알림 trigger/스키마도 before/after에 동일하게 적용하고 dispatcher는 비활성화한다. 과거 진단은 `output/oom-analysis-list-20261023`에 있으며 당시 H2 ORM SQL 수집은 PostgreSQL 성능 측정이 아니다.

## 조회 경계

기존 `/works/{workId}/analysis-jobs/batches?page=&size=`와 소유권 검사를 유지한다. 배치 정렬은 최근 요청 시각 내림차순, 배치 ID 내림차순이며 10개 페이지를 먼저 고른다. 선택된 배치의 scalar metadata만 조회한다. 새 `AnalysisBatchSummaryRepository`가 명시한 컬럼의 native CTE로 최신 작업 선택, 표시 상태, 차단 대기 여부, 목적/source별 숫자를 집계한다. 서비스는 집계 결과 조합과 기존 배치 상태 우선순위만 담당하고 Mapper는 응답을 조립한다.

회차 후보의 두 `countReviewSummaryByBatchIds`와 활성 세계관 비교 집계는 그대로 재사용한다. 이 쿼리는 EPISODE만 대상으로 한다. 설정집 카드는 선택된 원본 job ID별 후보 집계가 필요하므로 기존 상세 `countReviewSummary`의 directReview/processing 조건을 동일하게 적용한 다건 집계를 추가한다. 후보 엔티티나 후보 페이지 응답을 가져오지 않는다. 캐릭터·세계관을 먼저 각각 집계하여 조인 증폭을 막는다.

기존 top-level `jobGroups`와 `status`의 의미는 다른 소비자(원고 배너 등)를 위해 유지한다. 목록 전용 `episodeJobGroups`, `episodeStatus`, `settingBooks`를 추가한다. `episodeJobGroups`는 회차 이동 ID만 포함하고 표시 상태는 서버 판정을 따른다. `settingBooks`는 현재 페이지에 필요한 설정집 카드당 1개 요약이며 작업 이력 배열이 아니다. 필요한 내용은 settingBookId, 원본 analysisJobId/실제 batchId, source revisionId, 표시 이름, 표시 상태·typed failureCode·현재 단계, 변경/과거 여부, 마지막 변경 시각, 양쪽 후보 집계다. raw run/journal 상태, 전체 작업 DTO, 원문, 상태 JSON, errorMessage는 포함하지 않는다. 상세에서 쓰는 완료·중단·무효화 조건을 서버 요약 상태로 정규화한다.

설정집 primary action은 기존 동작을 보존한다: 진행/중단은 진행 화면, 완료 후 directReview+processing > 0은 검토, 0은 작품 설정, 과거 완료는 전체 이력. `candidateType`의 세계관 우선 선택과 원문 revision 이동, 반영·제외 이력 버튼도 보존한다. 목록은 `SettingBookAnalysisStatus`의 상세 조회 hook 대신 순수 요약 카드를 렌더링한다. 설정집 편집·진행 화면에서는 기존 상세 hook을 유지한다.

## SQL에 보존할 의미

1. 공개 작업에서 두 comparison 타입을 제외하고 workId와 page batchIds를 모든 출발점에서 제한한다. `select job.*` 금지.
2. EPISODE의 대상은 보존된 targetEpisodes가 있으면 그 연결, 없으면 episode_id, 둘 다 없으면 targetless다. 설정집은 source_setting_book_id를 identity로 삼는다.
3. `(batch, type, source kind, target identity)`마다 `row_number() over (... order by created_at desc, id desc)=1`을 선택한다. targetless는 같은 `(batch,type)`에 episode/book 선택이 전혀 없을 때만 fallback한다.
4. legacy 한 Job이 여러 회차를 대표하면 선택된 회차 행 수로 total/status 수를 계산한다. navigation ID는 distinct다. 이 규약은 기존 `getAnalysisBatchesCountsLegacyBatchWideJobPerEpisode`가 검증한다.
5. ordered INVALIDATED는 CANCELED. ordered SUCCEEDED이지만 journal이 SEALED가 아니거나 automaticAppliedAt 없는 AUTOMATIC은 FAILED. SQL null 비교도 Java의 `!= SEALED`와 같아야 한다.
6. 같은 run ID/generation/type 현재 선택 안에서 앞 sequence가 표시 FAILED/CANCELED이면 뒤 ordered PENDING은 차단된다. pending 숫자는 남기되 IN_PROGRESS 판정에서는 제외한다. 다른 generation의 과거 실패로 차단하지 않는다.
7. CONFIRMED_ONLY SETTING_EXTRACTION의 raw FAILED+quotaFailure+checkpoint WORLD_CANDIDATES_PUBLISHED/WORLD_COMPARISONS_FINISHED는 resumable token interruption이다. failed 표시 수는 유지하고 group failure 판정에서 제외하는 현 규약을 보존한다. checkpoint 문자열 사전순 비교 금지.
8. group 우선순위: running 또는 unblocked pending → IN_PROGRESS; canceled → CANCELED; non-resumable failed와 success 혼재 → PARTIALLY_FAILED, 실패만 → FAILED; 나머지 COMPLETED. 배치는 active world comparison을 최우선으로, 기존 resolveBatchStatus 순서를 유지한다.
9. 설정집 latest job은 현재 설정집의 latest 선택으로 연결되던 기존 동작을 확인하고, 같은 페이지의 legacy mixed batch에 episode와 book ID가 섞여도 각각 올바른 화면으로만 이동시킨다. 현재 revision과 분석 revision 차이에 따른 과거/변경 안내를 유지한다.
10. 최초/최근 요청 시각은 전체 이력 기준, lastActivity는 현재 선택 기준이다. 회차 수는 distinct episode이며 목적별 중복은 제거한다. 내부 비교 작업만 있는 배치는 공개 목록에 나타나지 않는다.

배치 메타·집계와 navigation ID는 별도 작은 결과 집합으로 반환해도 된다. 그 경우 같은 CTE 의미를 공유하고 Java가 과거 작업 전체를 다시 선별하지 않도록 한다. 새 인덱스는 실제 PostgreSQL EXPLAIN 결과에 필요성이 드러날 때만 추가한다.

## 완료 조건

실제 페이지 최초 진입 및 진행 polling에 전체 `/analysis-jobs`, `/episodes`, `/setting-books`, 개별 job/book 상세, 후보 목록 요청이 없다. 인증·작품 공통 요청과 batches는 허용한다. 원고 탭 진입 시 episodes가 정상 동작하고 진행 종료 갱신과 재분석 모달은 유지된다. 320/1280px, 페이지 이동/뒤로가기, 오류 재시도, 최신 retry, mixed source, 권한 격리를 통과한다.

개선 전후 같은 합성 fixture의 브라우저 ready와 API 시간, 요청 수·전송량, SQL, 메모리/GC 원자료를 보존한다. 측정 전 제품 코드를 수정하지 않는다. 운영 재시작·운영 부하 실행은 범위에 없다. 측정 프로토콜은 함께 저장한 계획의 Task 1을 따른다.
