# 분석 진행 상태 조회 (#291)

회차 진행 화면은 상태 확인용으로 큰 분석 JSON이나 후보 본문을 읽지 않는다. 기존 개별 작업 상세와 재시도 쓰기는 유지한다. 새 Backend 계약을 먼저 배포·확인한 뒤 Frontend를 적용한다.

## 작업 상태

`GET /api/v1/works/{workId}/analysis-jobs/progress?analysisJobIds=<comma-separated UUIDs>` (`getAnalysisJobProgress`)는 `CommonResponse<AnalysisJobProgressResponse[]>`를 반환한다. 중복 제거 전 1~100개를 허용하며 최초 입력 순서를 유지한다. 누락·빈 값·잘못된 UUID·101개는 400, 미인증은 401, 다른 작품/없는 작업이 섞이면 전체 404다. 내부 비교 작업은 기존 공개 API처럼 `ANALYSIS_JOB_TYPE_INVALID`로 거절한다.

응답은 `id`, `episodeId`, `jobType`, `status`, `reviewMode`, `automaticAppliedAt`, 공개 `failureCode`, `tokenInterruptedAfterExtraction`, `analysisRun`, `episodes`만 포함한다. `analysisRun`은 일반 분석에서 null이다. 실패 코드는 FAILED에서만 제공하며 누락 코드는 UNEXPECTED_ERROR로 정규화한다. 추출 보존 토큰 중단은 일반 SETTING_EXTRACTION/FAILED/AI_TOKEN_QUOTA_EXHAUSTED이며 WORLD_CANDIDATES_PUBLISHED 이상인 기존 조건을 유지한다.

소유권 확인 뒤 작업 scalar 조회 1회, 고정 대상 회차 scalar 조회 1회다. 대상 스냅샷이 있으면 ARCHIVED를 포함해 그 대상만 반환하고, 없으면 단일 episode_id, 둘 다 없으면 빈 목록이다. 회차는 ID·번호·제목·상태·수정 시각만 포함한다. JSON·결과 요약·원문·업로드 파일은 선택하지 않는다. JWT 회원/작품 조회는 기존 인증·인가 비용으로 별도 남는다.

## 세계관 비교 집계

`GET /api/v1/works/{workId}/world-setting-candidates/progress?batchId=<UUID>` (`getWorldSettingCandidateProgress`)는 `pendingComparisonCount`, `processingComparisonCount`, `tokenInterruptedComparisonCount` 세 nonnegative count만 반환한다. 작품과 배치를 확인하고 기존 countReviewSummary를 재사용한다. EPISODE 출처, 해당 batch, PENDING_REVIEW와 FAILED+AI_TOKEN_QUOTA_EXHAUSTED 조건을 유지하며 현재 작업 ID로 범위를 좁히지 않는다. 유효한 빈 배치는 모두 0, 없는/다른 작품 배치는 기존 404다.

## 클라이언트 갱신

한 Query가 작품·추적/현재/대체 ID 범위를 소유한다. 처음 추적 이력을 모두 읽고 이후 유효한 현재 ID만 갱신한다. 긴 이력은 최대 100개씩 순차 요청하며 마지막 묶음까지 성공한 뒤 교체한다. 실패·취소 시 일부 결과를 캐시에 쓰지 않고 마지막 정상 상태를 유지한다.

| 상태 | 반복 확인 |
| --- | --- |
| 미확인, RUNNING, 차단되지 않은 PENDING, 자동 저장 마무리 | 3초 |
| ordered 완료·실패·INCOMPLETE 또는 선행 실패 뒤 차단 대기만 존재 | 30초 |
| 종료된 무효화 작업·취소 또는 일반 종료만 존재 | 중지 |

무효화된 작업도 PENDING/RUNNING이면 종료 상태를 확인할 때까지 조회 대상에 남는다. RUNNING은 3초, 무효화로 차단된 PENDING은 30초이며, focus/reconnect와 수동 상태 재시도에도 같은 대상 규칙을 쓴다. 종료를 확인한 무효화 작업은 반복 조회를 멈춘다.

SEALED와 자동 반영 latch가 모두 있어야 자동 완료다. 완료 ordered의 30초 확인은 같은 ID 외부 재개·무효화를 발견한다. 명시 재개의 성공 응답은 추적/현재 ID와 URL을 먼저 보존한 뒤 묶음을 재조회한다. 재조회는 기존 읽기를 취소하고 응답을 캐시에 반영하므로 늦은 이전 상태가 재개 결과를 덮어쓰지 않는다. 응답 유실/409도 재조회한 상태로 판단한다. 비교 집계는 분석 진행 또는 대기/처리 비교가 있을 때 기존 3초 갱신과 사용량 알림의 명시적 재조회를 유지한다. 설정집 전용 sourceAnalysisJobId 경로는 그대로다.

초기 `/episodes`의 기존 상세 읽기와 재시도 경로의 JSON 로딩/복사는 이 변경으로 제거하지 않았다. 세계관 후보 검토 페이지의 #272 상세 페이지네이션도 범위 밖이다.
