# Analysis List Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 사용자가 정한 실행 방식은 GPT-6 Astra 계획·검증 / GPT-6.1 Sol ultra 구현이다. 이미 승인된 범위와 실행 방식에 대한 중복 승인 요청은 하지 않는다. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 분석 목록을 현재 페이지 카드 요약만 조회하도록 바꾸고 동일 조건의 실제 브라우저/API 개선 전후 측정으로 검증한다.

**Architecture:** 배치 페이지 선택 후 DB가 최신 작업·표시 상태·회차/설정집별 집계를 계산한다. 기존 회차 후보 count SQL은 재사용하며 목록은 요약만 렌더링하고 상세 조회는 클릭 이후 기존 화면이 담당한다.

**Tech Stack:** Java 21, Spring Boot 4/JPA, PostgreSQL 16, React 18, TanStack Query, Hey API, Playwright.

**Spec:** `../specs/2026-10-24-analysis-list-summary.md`

## Global Constraints

- 새 Backend/Front worktree에서 작업. Backend 기준 `0bf469df9c6db274ca12ce6a88f7d10115515899`, Front 기준 `49a1112fb1b6a6f9692b403e3d6482c56b25ffba`.
- baseline 원자료 수집 전 제품 코드 수정 금지. 운영 재시작·운영 부하 실행 금지.
- 백엔드 전체 엔티티/JSON 조회 또는 전체 작업 scalar 이력의 Java 집계로 대체 금지.
- SDK는 OpenAPI 생성기로만 갱신. 회차/설정집/legacy mixed, ordered/automatic/token 정책, 인증·소유권 유지.
- #272 세계관 검토 페이지네이션은 범위 밖. 결과 문서·이슈에는 측정/미측정/과거 관측을 구분.

## Review Focus

- 한 legacy Job이 여러 회차를 대표하는 경우 숫자는 회차 단위, 이동 IDs는 distinct.
- ordered journal NULL/INCOMPLETE/INVALIDATED, automatic 미반영, 다른 generation의 과거 실패.
- mixed batch의 book 재분석·revision 변경 및 완료 후보 directReview/processing 상태.
- 이전 페이지의 늦은 응답·polling·뒤로가기 이후 다른 source로 이동하지 않음.
- 원고→분석 탭 이동 후 episodes polling 중지와 원고 재진입/재분석 모달 보존.

---

### Task 1: 변경 전 실앱 측정과 재실행 가능한 fixture

**Files (Backend):** Create `docs/load-tests/2026-10-24-analysis-list.md`, `scripts/load-tests/analysis-list/README.md`, fixture/runner scripts in that directory, sanitized `docs/load-tests/results/2026-10-24-analysis-list/`.
**Files (Front):** Create `scripts/analysis-list-benchmark.mjs` (Playwright 실행기; 실 API만 사용).
**Interfaces:** 입력은 명시한 localhost frontend/API/격리 DB 및 local credential 파일. 출력은 환경 manifest, fixture hash, request/ready/API samples JSONL와 SQL before. 토큰·쿠키·본문·인증 헤더는 출력하지 않는다.

- [ ] 기존 `docs/load-tests`와 `e2e/local-live-fixture.ts` 패턴을 읽고 Docker/Java/Node/browser 버전을 기록한다. 격리된 로컬 PostgreSQL과 e2e API를 준비하고 `.env` import를 차단한다. API/JVM heap은 `-Xmx768m`, worker/scheduler는 fixture를 바꾸지 않도록 비활성화한다. CPU/memory 제한, JVM 실제 옵션, DB 버전, frontend 빌드 모드와 browser/viewport를 manifest에 저장한다. 같은 리소스가 before/after 모두 가능하지 않으면 측정을 시작하지 말고 부모에게 구체적 장애를 보고한다.
- [ ] 결정적인 합성 seed를 만든다: 60 공개 작업/60 회차/2 배치, 1–30화 18 성공+12 취소, 31–60화 2 성공+1 실패+27 차단 대기. 398 캐릭터·409 세계관 후보(캐릭터 342/56, 세계관 296/113)를 합성하고 분석 JSON 총 UTF-8 논리 크기를 목표 15,460,388 bytes에 맞춘다. 전체 데이터/분포 해시와 실제 `octet_length` 합계를 검증한다. 동일 문자 반복만 사용한 과도한 압축 fixture를 피하고 고정 seed 난수 기반 유효 JSON을 사용한다. 이것은 실데이터의 크기·상태 모델이지 운영 데이터를 복제한 것이 아님을 명시한다.
- [ ] 설정집-only/mixed/retry/targetless/page2 fixture는 correctness 용으로 별도 만든다. 운영 원문을 사용하지 않는다. 동일 fixture 재실행 시 식별자·시각·개수·크기가 같음을 확인한다. PostgreSQL JSONB가 공백·키 순서를 정규화하므로 목표 크기는 생성 파일 크기가 아닌 DB의 `octet_length(column::text)` 합으로 맞춘다.
- [ ] pinned baseline의 실 API+frontend를 실행한다. 인증은 측정 전에 완료한다. production build/preview를 우선 사용하고 Vite dev를 쓰면 before/after 모두 같은 모드와 warmed transform 조건을 명시한다. 측정 중 SQL debug·추적 exporter 등 부가 계측을 동일하게 끈다.
- [ ] 3회 준비 실행 후 20회 직렬 fresh browser context 탐색을 측정한다(동일 viewport 1280x800, 인증만 주입, 브라우저 HTTP cache·React Query는 매회 비움; DB/JVM은 warm). `performance.timeOrigin` 기준 navigation `loadEventEnd`와 **2개 카드의 상태/숫자/활성 primary button이 모두 표시되고 2 animation frames 뒤**의 pageReady를 별도 저장한다. 버튼 클릭은 측정 후 별도 correctness run에서 확인한다. networkidle을 준비 완료로 쓰지 않는다. 각 request의 route, status, 시작/끝, response bytes를 기록한다. 60초 timeout은 누락 대신 실패 샘플로 기록한다. 새로고침 2회 연속 시나리오도 별도 5세트 직렬 실행해 누수/OOM과 경로 중복을 확인한다.
- [ ] API latency는 같은 warm 조건에서 batches 3 warmup+30회 직렬 샘플로 측정하고 median/p95/max를 저장한다. 전체 jobs와 episodes는 baseline 네트워크 경로로 식별하되 after 페이지에서 제거되었는지 비교한다. 프로세스 RSS/JVM heap/GC pause·count와 OOM·5xx를 같은 sampling 방식으로 기록한다. JVM 초기 한 번의 cold sample은 별도로 표기하며 DB/OS cache를 비웠다고 주장하지 않는다.
- [ ] 타이밍 run과 별도로 동일 fixture의 SQL 로그 및 `EXPLAIN (ANALYZE, BUFFERS)`를 로컬에서 수집한다. 기존 batch-page, batch-jobs, all-jobs, episode-summary-jobs 컬럼을 보존한다. 빈 H2 진단 SQL을 실제 Postgres 실행 시간으로 쓰지 않는다. baseline 샘플/manifest/hash가 파일에 존재함을 확인한 뒤에만 Task 2로 간다.

### Task 2: DB 최신 선택과 요약 집계

**Files (Backend):** Create `domain/analysis/repository/AnalysisBatchSummaryRepository.java` and focused aggregate row records/interfaces there; Modify `AnalysisJobServiceImpl.java`, `AnalysisBatchMapper.java`, `AnalysisBatchPageRow.java`, `AnalysisJobRepository.java`; tests `AnalysisJobControllerIntegrationTest.java`, new `AnalysisBatchSummaryPostgresIntegrationTest.java` in analysis/repository. Paths are under `src/main/java/org/monitoring/catchholebackend/` or matching `src/test/java/`.
**Interfaces:** `readSummaries(UUID workId, List<UUID> batchIds)` returns only batch/group aggregates and tiny navigation IDs; public `getAnalysisBatches(Long memberId, UUID workId, int page, int size)` remains unchanged. Empty batchIds returns no SQL. Page row gains uploadType scalar so Mapper does not need UploadBatch entity.

- [ ] Extend existing API tests before query replacement: same-timestamp UUID tie; legacy snapshot-over-episode precedence and one job/many target count; targetless fallback hidden by episode/book; comparison-only and empty page; foreign owner. Existing latest retry, ordered blocked, canceled and pagination tests remain assertions of the reference behavior.
- [ ] Add PostgreSQL integration fixture for ordered NULL/INCOMPLETE/INVALIDATED and missing automaticAppliedAt, different generation, blocked pending with completed prefix, and resumable token failure at both allowed checkpoints. Assert exact group numbers/status/current IDs and candidate counts. Use the existing Testcontainers pgvector PostgreSQL16/Flyway pattern. H2 success alone is insufficient.
- [ ] Implement explicit-column CTE latest selection and aggregate according to all 10 spec rules. Let DB compute status predicates/aggregates; tiny ID rows may be grouped in Java. Keep work ownership before repository work and existing candidate count queries. Remove only now-unused private entity-list aggregation helpers; do not change detail/retry paths.
- [ ] Capture list SQL with a cleared persistence context and assert it selects none of `automatic_input_state`, `run_base_state`, `state_journal`, raw result/text/error columns; no AnalysisJob/candidate/setting-book-body entities materialize for this read. Verify query count does not grow once per job. Compare before/after EXPLAIN; add an index only if justified by recorded plan evidence.
- [ ] Run targeted API and Postgres tests. Confirm tests executed rather than skipped because Docker is unavailable; report skipped Postgres as unverified. Commit this independently reviewable backend change using project commit rules after the parent authorizes its normal workflow.

### Task 3: source별 카드 계약과 설정집 count

**Files (Backend):** Modify `AnalysisBatchSummaryResponse.java`, `AnalysisBatchMapper.java`, summary repository; Create `AnalysisBatchSettingBookResponse.java` and minimal candidate-count response/row if needed; Modify both `SettingCandidateRepository.java` and `WorldSettingCandidateRepository.java`; extend API/Postgres tests from Task 2.
**Interfaces:** Keep existing response fields; add `episodeJobGroups: List<AnalysisBatchJobGroupResponse>`, `episodeStatus: AnalysisBatchStatus`, `settingBooks: List<AnalysisBatchSettingBookResponse>`. Book response fields are the card projection in the spec, never `AnalysisJobResponse`. Candidate read is grouped by selected origin job ID and returns total/reviewed/pending/directReview/processing per candidate kind.

- [ ] Add failing tests for book-only, legacy mixed and book latest retry across revision; mixed episode navigation IDs contain no book IDs and vice versa. Assert unselected books/jobs do not appear. Book candidates count only their origin job; episode batch totals exclude book candidates. Include zero candidates and directReview=0 with processing>0.
- [ ] Implement card summaries with ordered completion/interruption/invalidation semantics and explicit revision/title metadata. Reuse existing countReviewSummary predicates to avoid changing review eligibility. No per-book service call to existing detail API/repository entity paths. Preserve current latest-book redirect behavior and historical/changed flags.
- [ ] Verify top-level legacy consumers retain their group/status contract while episode-only groups use consistent ordered semantics. Book response supplies enough data for primary/review/history/original-source navigation and quota notification without fetching details.
- [ ] Run targeted tests, then `./gradlew test` and `./gradlew bootJar`; capture OpenAPI from the implementation API for Task 4. Record failures or resource limitations without treating them as passes.

### Task 4: 목록은 요약만 렌더링

**Files (Front):** Modify `src/app/components/catchhole/AnalysisList.tsx`, `S1Dashboard.tsx`, generated SDK through generator; Create `AnalysisListBookCard.tsx` only if useful to isolate pure rendering. Update `e2e/analysis-list.spec.ts`, relevant `e2e/setting-book.spec.ts`, `e2e/ordered-analysis.spec.ts`, `e2e/token-interruption.spec.ts` fixtures; existing detail `SettingBookAnalysisStatus.tsx` remains the clicked-screen implementation.
**Interfaces:** `AnalysisList` consumes new summaries exclusively. Episode progress URL retains `analysisJobIds/currentAnalysisJobIds/jobType`; book progress retains `sourceAnalysisJobId`. Review/history/source links keep existing URL contracts. Both cards receive plain data, no per-card query.

- [ ] Regenerate SDK with `CATCHHOLE_OPENAPI_INPUT=<local implementation OpenAPI URL> npm run api:generate`; never hand-edit generated files.
- [ ] Add E2E assertions that initial list and one polling interval request no whole jobs/episodes/books, no job/book detail and no candidate APIs. Test episode-only/book-only/mixed/page2 fixtures with correct labels/counts/enabled buttons, 320/1280px and each navigation destination. A fully supplied summary must enable actions without the former sourcesReady lookup.
- [ ] Remove jobsQuery/booksQuery/source reconstruction and render server episode groups plus pure book cards. Keep query key/page parameters, error retry, token interruption notification and active-status polling. Use summary state to preserve setting-book completion cache invalidation/quota notifications as appropriate without reintroducing hidden detail requests.
- [ ] Gate parent episodes query AND polling to manuscripts (and any real still-open episode-dependent modal if navigation leaves it mounted). Verify manuscripts→analyses stops further episode fetches, analyses→manuscripts resumes, current reanalysis modal counts and completion feedback remain correct. Keep changes confined to query activation; do not redesign unrelated tabs.
- [ ] Run focused E2E, `npm run lint`, `npm run typecheck`, `npm run build`. Run live local click-through checks for progress/review/source with real auth/API and confirm backend ownership errors remain errors. A mocked page timing is never the performance result.

### Task 5: 동일 조건 after 측정과 결과 기록

**Files:** Task 1 harness/results/report, Backend/Front `AGENTS.md` only for the new persistent summary/query convention, Front `docs/analysis-list-summary.md` describing the shared contract and linking backend evidence.
**Interfaces:** After runner accepts identical fixture manifest/protocol and writes separate after files; it never overwrites before samples. Parent owns issue/PR posting and cross-repository coordination.

- [ ] Restore the same seed and verify its exact manifest/hash/row counts/JSON byte sizes. Restart only the isolated local measurement app to equivalent conditions, use same JVM/resource limits and runner. Execute the same page/API/reload samples and SQL evidence capture. Preserve failures and raw samples, not just best timings.
- [ ] Compare median/p95/max pageReady and window load, route-by-route API latency, request count/bytes, selected SQL columns/plans, peak RSS/heap and GC/OOM. Compute `(before-after)/before` only for comparable positive measurements. Explicitly state local synthetic limits and retain any regression rather than changing protocol post hoc.
- [ ] Write concise problem→SQL old/new→request graph old/new→measured table→correctness tests→limitations in the load-test report. Include commands, hashes, resource/cache/sample protocol, raw evidence locations and whether each check actually ran. Do not equate no local OOM with a proven production memory bound.
- [ ] Have GPT-6 Astra verify query semantics, source navigation, test evidence, raw sample accounting and absent detail requests. Fix substantive findings and rerun affected verification only. Send parent the document paths and factual issue update text; parent handles issue/PR/commit decisions.

## 실행 환경 검토 메모 (2026-10-24)

- 기존 `gh221-local-postgres`/localhost:15432와 원래 `compose.yaml`은 사용·변경하지 않는다. 별도 이름/포트/볼륨의 PostgreSQL16을 만들고 `spring.docker.compose.enabled=false`, `spring.config.import=`를 명시하여 기본 compose 자동 실행과 `.env` import를 모두 막는다. 새 V88 migration도 baseline과 after에 동일 적용한다.
- e2e profile은 저장소 접근을 LocalFileObjectStorage로 바꾸고 fake mail/SMS를 쓰지만 scheduler를 모두 끄지는 않는다. `analysis.metrics`, `service-usage.metrics`, `work.purge`, `episode.source-purge`, `member.withdrawal`, `world-image.cleanup`, `relationship.processing`, `chat.session-cleanup`의 scheduling-enabled 및 `chat.metrics-enabled`를 false로 설정하고 `analysis.notifications.enabled=false`를 고정한다. ChatDispatcher.recover는 chat.enabled=false에도 매 주기 recoveryIds를 읽으므로 `chat.recovery-delay-ms=86400000`으로 맞추고 시작 직후 조회는 warmup 전에 끝낸다. Worker는 실행하지 않는다.
- frontend는 빌드 시 `VITE_API_BASE_URL`로 실제 격리 API URL을 지정한다. preview 포트를 localhost CORS allowed-origins에 명시한다. 같은 localhost/127.0.0.1 표기를 유지해 쿠키·origin 차이를 피한다. 인증은 seed 회원으로 준비하고 이메일/휴대폰 회원가입 절차를 성능 측정에 넣지 않는다.
- 이 planner 세션에서는 Docker socket 접근이 거부되었다. 부모 세션에서 접근 가능하다는 확인과 구분하며 Docker 실행/자원 확보 검증은 harness 실행자가 기록한다. Node가 PATH에 없으면 `/Users/junewoo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node`(확인한 버전 v24.19.0)를 사용할 수 있다. Java는 Microsoft OpenJDK 21.0.12.1 확인. 환경 버전은 최종 runner가 실제 사용한 값으로 덮어 기록한다.
- 15,460,388 bytes는 기존 `analysis-list-size-check.sql`의 저장된 결과 `60 | 15460388 | 15460388 | 1 | 0`에서 확인했다. JVM retained heap/HTTP bytes로 해석하지 않는다. 캐릭터·세계관 개수 및 배치 분포는 기존 `issue-comment-20261023.md` 21–22행에 기록되어 있다.
