# Analysis Progress Bulk Read Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace episode progress detail fan-out with bounded scalar reads while preserving resume, invalidation and automatic completion.

**Architecture:** One backend scalar job query plus one target projection behind a new bounded GET; a separate comparison-count GET reuses the existing scalar aggregate. One frontend query owner batches IDs and uses 3s active/30s quiescent ordered polling, retaining historical data.

**Tech Stack:** Java21, Spring Boot/JPA/PostgreSQL, React18/TanStack Query5, generated Hey API SDK, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-24-analysis-progress-summary.md`

## Global Constraints

- BE baseline `79c2b0e`; FE baseline `74d10ec`; worktrees are sibling backend/frontend directories.
- API input 1–100 raw IDs; 3,000ms active and 30,000ms idle ordered polling; Java benchmark max heap 768MiB.
- No product source changes outside progress read path; preserve source/legacy/retry semantics; no retry-write redesign, #272 changes, production load or deployment.
- Generated SDK is regenerated from backend OpenAPI, never hand edited. Korean API descriptions/errors/tests follow AGENTS.md.
- User already authorized implementation and designated GPT-6.1 Sol ultra; proceed to one implementer and independent GPT-6 Astra review without another approval checkpoint. Commits require explicit authorization under FE AGENTS.md.

## Review Focus

- Same-ID resume after 30s idle must switch to fast polling immediately on local retry.
- Blocked pending does not force perpetual 3s idle polling, yet resumes after predecessor recovery.
- SEALED without automaticAppliedAt must not unlock success prematurely.
- Legacy multi-target snapshots/ARCHIVED targets survive without entity hydration.
- Failed or canceled multi-chunk refresh cannot partially replace successful state or leak across work changes.

---

### Task 1: Deliver the complete backend/frontend progress contract and measured regression proof

One coherent implementation/review unit: independent partial shipping would leave the page on the old path or call an unavailable endpoint. Organize code by existing responsibilities, avoid unrelated refactoring.

**Files** (backend paths under `src/main/java/org/monitoring/catchholebackend/domain/analysis/`):
- Modify `controller/AnalysisJobController.java`, `service/AnalysisJobService.java`, `service/AnalysisJobServiceImpl.java`, `repository/AnalysisJobRepository.java`.
- Create `dto/response/AnalysisJobProgressResponse.java`, `dto/response/AnalysisJobProgressEpisodeResponse.java`, `repository/AnalysisJobProgressRow.java`, `repository/AnalysisJobProgressEpisodeRow.java`, `mapper/AnalysisJobProgressMapper.java`.
- Modify worldsetting `controller/WorldSettingCandidateController.java`, `service/WorldSettingCandidateService.java`, `service/WorldSettingCandidateServiceImpl.java`; create `dto/response/WorldSettingCandidateProgressResponse.java`; reuse existing aggregate/mapper conventions.
- Create tests `src/test/java/org/monitoring/catchholebackend/domain/analysis/controller/AnalysisJobProgressControllerIntegrationTest.java` and `repository/AnalysisJobProgressPostgresIntegrationTest.java`.
- Frontend: create `src/app/hooks/useAnalysisJobProgress.ts` (use existing hooks location if repo convention differs); modify `src/app/components/catchhole/SEpisodeUpload.tsx`, `src/app/lib/ordered-analysis.ts`; regenerate `src/app/api/generated/`.
- Frontend tests: create `e2e/analysis-progress-summary.spec.ts`; update affected existing ordered/retry/upload fixtures in `e2e/ordered-analysis.spec.ts` and other specs found by endpoint search.
- Benchmark: create backend `scripts/load-tests/analysis-progress/` dedicated fixture/runner instructions, frontend `scripts/analysis-progress-benchmark.mjs`, backend `docs/load-tests/2026-10-24-analysis-progress.md` and sanitized result files. Reuse prior resource/SQL/manifest helpers rather than duplicate them.
- Update frontend `docs/data-requirements/analysis.md`, `docs/analysis-recovery.md`, both applicable AGENTS.md progress-contract notes; backend analysis API docs as needed.

**Interfaces:**
- Consumes current work authorization, `assertPublicJobType`, scalar checkpoint and AnalysisRunResponse contracts.
- Produces `List<AnalysisJobProgressResponse> getAnalysisJobProgress(Long memberId, UUID workId, List<UUID> analysisJobIds)`; controller operation `getAnalysisJobProgress`; repository `findProgressByWorkIdAndIdIn` and `findProgressEpisodesByWorkIdAndJobIdIn` scalar queries.
- Produces `WorldSettingCandidateProgressResponse getWorldSettingCandidateProgress(Long memberId, UUID workId, UUID batchId)` and generated operation of the same name; exactly three fields per spec.
- Produces `useAnalysisJobProgress({workId, trackedAnalysisJobIds, currentAnalysisJobIds, supersededJobIds, enabled})` returning jobs, isError, refetch and progress-cache refresh support. Internal key is scoped by work and all three ID sets. Exact DTO fields and interval decision are fixed in spec.

- [x] Step 1: Preserve/build before artifacts from baseline commits, record hashes, prepare local 30-job fixture and measure before page/API/SQL/resources according to spec. Read previous benchmark README and safety guards before provisioning. Do not reuse #288 before artifacts as #291 baseline.
- [x] Step 2: Add failing MockMvc tests: 1/30/100 accepted; 0/101/malformed rejected; duplicate order preserved; anonymous401, nonowner/cross-work/missing404; internal job denied per old endpoint; response contains only declared scalars/episodes. Run `./gradlew test --tests '*AnalysisJobProgressControllerIntegrationTest'` and retain expected failure.
- [x] Step 3: Add failing PostgreSQL projection tests patterned on `AnalysisBatchSummaryPostgresIntegrationTest`. Assert snapshot-over-episode fallback, multi-target/archived/targetless cases, token checkpoint before/at/after publication, FAILED-null public failure mapping, ordered run/latch fields. Capture executed SQL/entity statistics after flush/clear for 1 and 30 jobs and assert constant number of reads and zero heavy fields/entity loads. Use that existing test’s disposable pgvector PostgreSQL16 Testcontainers pattern, with a dedicated progress-test database name; run `./gradlew test --tests '*AnalysisJobProgressPostgresIntegrationTest'` with Docker available. A skipped result is not a pass.
- [x] Step 3b: Add failing count-endpoint MockMvc/PG tests in `WorldSettingCandidateProgressControllerIntegrationTest` and the progress PG integration suite. Assert exactly three count fields, empty zero, mixed-source/batch/review/failure scope, 400/401/404 and constant scalar query shape without candidate/job entity or JSON loads.
- [x] Step 4: Implement DTO/projections/mapper and service/controller method. Use explicit scalar SELECT lists and bulk target membership query with snapshot precedence. Validate size at HTTP and service boundary before dedupe, validate complete requested set before returning, preserve order. Avoid copying the existing detail mapper or loading entities to derive booleans.
- [x] Step 4b: Implement separate comparison progress GET using existing batch aggregate and read authorization/validation. Do not invoke getCandidates or fetch candidate pages; map only three counts.
- [x] Step 5: Run focused backend tests; boot updated local API and regenerate via `CATCHHOLE_OPENAPI_INPUT=http://127.0.0.1:<isolated-port>/v3/api-docs npm run api:generate`. Verify null enum schema and generated nullable types. Expected: tests pass without skipped required PG coverage; generated operation/query options exist.
- [x] Step 6: Add failing Playwright tests with fake clock where possible: 30 IDs=one bulk/zero detail requests; before3s no extra request then active3s; failed+blocked idle no3s request then30s; completed ordered external resume and invalidation discovered; SEALED without latch remains unfinished and polls3s; local same-ID retry refreshes immediately. Also assert >100 chunks, history once, canceled/terminal-invalidated interval stop while invalidated PENDING/RUNNING remains eligible until terminal, failure preserves rows and manual retry bulk, late-response cancellation/work isolation, legacy targets and setting-book route unchanged. Successful replacement retry IDs must persist before a stalled follow-up read, and stale pre-retry responses must not overwrite resumed state. Add independent comparison-summary polling/count tests, zero full candidate page/detail requests and quota notification refetch behavior.
- [x] Step 7: Implement hook and integrate page. Use generated SDK with AbortSignal and sequential100-ID chunks; merge successful rounds only. Preserve history cache, narrow ordered helper input types, use existing work title. Replace status error retry and seed/invalidate bulk query for retry/restart responses; cancel stale reads before seeding. Keep all current-ID/URL/superseded/quota/type-check handling. Replace worldComparisonSummaryQuery with generated getWorldSettingCandidateProgress options, preserving batch scope, enabled/interval and explicit notification refetch. Update old E2E routes to new contracts without dropping assertions.
- [x] Step 8: Run `npm run test:e2e -- e2e/analysis-progress-summary.spec.ts e2e/ordered-analysis.spec.ts e2e/analysis-list-summary.spec.ts e2e/setting-book-analysis.spec.ts` plus any upload/retry specs changed. Run frontend lint/typecheck/build/check:unused/doctor and backend full `./gradlew test bootJar`; report unrelated preexisting findings, do not mask them. Run actual authenticated browser smoke on isolated API, including cookie/session behavior and owned/cross-work requests.
- [x] Step 9: Build after artifacts, verify identical fixture hash and environment, measure same windows and collect SQL/EXPLAIN. Report all routes, not only job status; prove no heavy fields in progress SQL and 30-job fan-out elimination, with honest latency/heap/GC results and failure counts. Sanitize evidence; keep secrets/raw personal paths outside tracked report. Document commands and limitations.
- [x] Step 10: Self-review against five review-focus cases and spec; update docs/AGENTS for new API and polling convention. Present working diff and test/performance report to independent GPT-6 Astra reviewer. Fix actionable findings and rerun relevant tests. Hand root exact paths, results, remaining risk (retry write path unchanged), and backend-before-frontend rollout requirement. Do not commit/push without user authorization.
