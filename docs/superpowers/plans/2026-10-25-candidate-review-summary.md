# Candidate Review Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unused AnalysisJob JSON from candidate lists and replace character review's world-detail count request with a scoped summary API.

**Architecture:** Keep candidate entities and existing group semantics; bulk-read immutable Job scalar metadata and pass it explicitly to list mapping. Reuse existing world aggregate queries behind a small summary DTO, then consume the generated SDK with unchanged UI state and polling.

**Tech Stack:** Java 21, Spring Boot 4/JPA, PostgreSQL, React 18, TanStack Query, Hey API, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-25-candidate-review-summary-design.md`

## Global Constraints

- No production access, deployment, commit, or push. Keep changes reviewable in the two existing worktrees on `perf/gh-296-candidate-review-summary`.
- Preserve list DTOs, grouping/ordering/pages, source scope, auth, metadata and mutation behavior. No group pagination redesign or GH294 Worker write changes.
- No partial AnalysisJob reconstruction or entity-backed lazy fallback on the optimized read path.
- Generated FE API files come only from Backend OpenAPI. Existing `/progress` remains unchanged.
- User has selected Astra planning/review and GPT-6.1 Sol ultra implementation; execution is authorized without a further confirmation gate.
- Isolated benchmark ports: PostgreSQL27432, API29480, FE3440. The parent coordinates timing so builds/tests do not run during measurement.

## Review Focus

- Setting-book old origins, changed/archived revisions and null S3 versions stay readable with exactly the old actionability; Task 1 parity/scope tests.
- Hidden comparison Job hash belongs to the comparison batch, not the extraction origin; Task 1 mapper+SQL test.
- Optional legacy candidate page and helpers can silently reinitialize Job proxies; Task 1 actual HTTP SQL test covers both includeLegacyCandidates values.
- World edits followed by tab switching and source A→B navigation must not reuse stale counts; Task 2 cache invalidation E2E.
- Pending automatic application and failed/deferred preparation must retain manual-review/count semantics, including off-page candidates; Task 1 state matrix and Task 2 polling E2E.

---

### Task 1: Backend metadata read boundary and world summary

**Files:** paths below are relative to BE `src/main/java/org/monitoring/catchholebackend/` unless noted.

- Create `domain/analysis/repository/CandidateReviewJobMetadata.java` (immutable scalar read value) and `CandidateReviewMetadataRepository.java` (scoped bulk metadata queries).
- Create `domain/analysis/processor/AnalysisReviewState.java` (pure status predicates; no persistence or writes).
- Modify `domain/analysis/service/CandidateReviewSourceScope.java` with a read-only scalar validation/range path, preserving mutation entry points.
- Modify `domain/character/repository/SettingCandidateRepository.java`, `domain/worldsetting/repository/WorldSettingCandidateRepository.java` with list-only no-Job-fetch methods.
- Modify `domain/character/entity/SettingCandidate.java`, `domain/worldsetting/entity/WorldSettingCandidate.java` only for shared pure predicate overloads/delegation if required; mutation conditions do not change.
- Modify `domain/character/mapper/SettingCandidateMapper.java`, `domain/worldsetting/mapper/WorldSettingMapper.java`, `domain/analysis/mapper/AnalysisCurrentSourceTimeMapper.java` for explicit metadata mapping; keep old detailed/mutation entry points.
- Modify both `SettingCandidateServiceImpl.java` and `WorldSettingCandidateServiceImpl.java`; world `WorldSettingCandidateService.java` and `WorldSettingCandidateController.java`.
- Create `domain/worldsetting/dto/response/WorldSettingCandidateSummaryResponse.java`; summary assembly belongs in `WorldSettingMapper`.
- Tests: existing candidate service/mapper/controller tests; create `src/test/java/org/monitoring/catchholebackend/domain/analysis/repository/CandidateReviewReadPostgresIntegrationTest.java` and `domain/worldsetting/controller/WorldSettingCandidateSummaryControllerIntegrationTest.java`.
- Update BE `AGENTS.md` analysis/character policy with the new read boundary and summary API; keep explanation brief.

**Interfaces:**

- Public endpoint, exact response fields and service signature are frozen in the spec. Publish live local OpenAPI as soon as controller+DTO compile so Task 2 can generate against it.
- `CandidateReviewMetadataRepository.findByIds(Collection<UUID> jobIds)` returns `List<CandidateReviewJobMetadata>` using scalar columns only; empty input returns empty without an IN query. IDs originate only from owned/scoped candidates or validated source, not unvalidated public bulk input.
- `CandidateReviewSourceScope.validateRead(Work work, UUID batchId, UUID sourceAnalysisJobId)` returns nullable `CandidateReviewJobMetadata`, checks extraction/work/batch exactly like existing validate and uses no entity fallback. Reuse the returned value for scoped episode range.
- List mapper overloads accept the immutable metadata map explicitly; single-candidate mapping resolves origin and optional comparison-batch Job metadata from it. Detail/mutation overloads retain their signatures. A missing required metadata row is not silently mapped to default booleans.
- `AnalysisReviewState` carries mode/reviewMode/status/journalStatus/automaticAppliedAt/currentSourceVersion, with pure completedOrdered/automaticPending/ordered/automatic predicates equal to existing AnalysisJob rules. Candidate manual-review/deferred-failure policies share these values; no changing Job state.

- [x] **1. Write failing scope/summary/SQL regression tests.** Add tests `summaryMatchesListMetadataWithoutCandidates`, `rejectsForeignBatchWorkAndComparisonJob`, `readsHistoricalBookWithoutMakingItActionable`, and `listAndSummaryDoNotLoadAnalysisJobJson`. Assert all spec fields and 401/404 behavior, empty 0 summary, EPISODE default and explicit origin scope. In real PostgreSQL, seed nonempty three JSON fields, flush/clear, reset SQL capture+Hibernate statistics, invoke authenticated MockMvc requests, assert zero AnalysisJob loads and no forbidden selected columns; summary also zero candidate loads. Include character legacy true/false and setting-book source.
- [x] **2. Run those tests and retain the expected red failures.** Use `./gradlew test --tests '*CandidateReviewReadPostgresIntegrationTest' --tests '*WorldSettingCandidateSummaryControllerIntegrationTest'`; expect missing endpoint/projection or captured heavy JSON, not fixture setup failure.
- [x] **3. Implement scalar metadata and read-only source scope.** Include source/reference-time response fields, current-source checks with null-safe equality, latest origin tie-break and comparison input hash. Add `reviewStateMatchesExistingPredicates` tests across MANUAL/AUTOMATIC, CONFIRMED_ONLY/ORDERED, PENDING/RUNNING/SUCCEEDED/FAILED/CANCELED, SEALED/INCOMPLETE/INVALIDATED, current/stale sources and automatic latch. Do not weaken source validation for archived books.
- [x] **4. Wire list-only reads and mapping.** Retain old mutation repositories/mapper calls. Fetch needed metadata in bounded bulk calls, pass it through group and legacy mapping, replace every Job-dependent list helper (including snapshot mode, canApply, failed-preparation and temporal policy). Keep complete candidate/batch context needed for previews. Slice world group entries before mapping to avoid off-page DTO/provenance work while preserving keys/count/order.
- [x] **5. Implement summary endpoint and shared count assembly.** Reuse countReviewSummary, activeComparisonJobCount and episode range with the same source argument; list and summary derive their top-level fields from the same assembly. Declare authenticated OpenAPI operationId `getWorldSettingCandidateSummary`, CommonResponse and 400/401/404 errors in Korean. Do not call getCandidates from summary. Expose the local schema for Task 2.
- [x] **6. Pin full mapping parity.** Tests compare metadata-aware list mapping to existing mapping for episode/book sources, compared/targetless candidates, actual vs provisional character, comparison batch hash, ordered frozen before snapshot, null S3 versions, stale/latest origin, discovery, manual reviewed mode and deferred candidate failures. Test stable group/page identities with same timestamp UUID ties, unknown character last, final world decision regrouping, empty/out-of-range pages and filters whose summaries still count off-page processing rows.
- [x] **7. Run focused and full checks.** `./gradlew test --tests '*SettingCandidate*Test' --tests '*WorldSettingCandidate*Test' --tests '*CandidateReviewReadPostgresIntegrationTest'`, then `./gradlew test bootJar`. Preserve existing mutation tests and exact outcomes. Check actual captured SQL after serialization, not just repository SQL. Record tests and uncommitted diff for Astra review; do not commit.

### Task 2: Frontend world summary SDK, query and invalidation

**Files:** relative to FE sibling `../frontend/`.

- Regenerate `src/app/api/generated/` from Task 1 OpenAPI.
- Modify `src/app/components/catchhole/characterreview/CharacterSettingReview.tsx`, `src/app/components/catchhole/worldsetting/WorldSettingReview.tsx`, `src/app/lib/setting-review-progress.ts`, `src/app/lib/analysis-source.ts`.
- Modify `e2e/review-summary-loading.spec.ts`, relevant shared fixture handlers and existing setting-review/source/automatic/navigation tests; add `e2e/candidate-review-summary.spec.ts` for focused request/polling/cache behavior.
- Update `AGENTS.md` near candidate review API guidance with the summary read contract.

**Interfaces:** consumes `getWorldSettingCandidateSummaryOptions`, `getWorldSettingCandidateSummaryQueryKey`, `WorldSettingCandidateSummaryResponse` generated from Task 1. Query arguments are `{path:{workId},query:{batchId,sourceAnalysisJobId}}`, without list filters or page parameters. Emits the same rendered review states as before.

- [x] **1. Write failing E2E contracts.** Assert character tab requests `/world-setting-candidates/summary` and never a world candidate list for counts; capture correct optional sourceAnalysisJobId. Delayed/error summary must preserve existing loading/error behavior and never enable completion using fabricated zero. Keep background successful data during refetch error.
- [x] **2. Run focused tests before changing production code.** `npm run test:e2e -- e2e/candidate-review-summary.spec.ts`; expected failures are old list request/missing summary route.
- [x] **3. Regenerate from the live isolated backend.** `CATCHHOLE_OPENAPI_INPUT=http://127.0.0.1:29480/v3/api-docs npm run api:generate`. Coordinate API startup with parent; do not patch generated code or create a hand-written competing SDK.
- [x] **4. Replace worldSummaryQuery and adjust types.** Preserve enabled/retry, source gate, existing 2-second processing polling with existing fallback, error rendering and all existing total/confirmed/dismissed/directReview/processing/completion uses. Add the summary type to shared summary typing or use the exact common count field Pick; no shape cast pretending a summary has groups.
- [x] **5. Preserve invalidation across paths.** Add summary operation to `invalidateAppliedSettings`; invalidate scoped summary wherever world list mutations/resume/decision-save recovery invalidated its former size=1 cache. Preserve existing cancellation/recovery semantics and unrelated scopes. Update mock route handlers and operation-ID invalidation predicates so tests truly exercise the summary instead of an empty fallback object.
- [x] **6. Test polling and cache transitions.** A processing count found outside current groups keeps the existing 2-second polling; transition to zero stops it. World confirm/dismiss then switch to character must show fresh totals; source A→B and different work IDs cannot reuse summary; stale book source remains read-only. Keep default character pending/size20/includeLegacyfalse unchanged. Initial 3-second wording was corrected against the baseline constant; runtime behavior is preserved.
- [x] **7. Verify and review.** Run `npm run lint`, `npm run typecheck`, `npm run build`, `npm run test:e2e`, `npm run check:unused`, `npm run doctor`. Use local real API/DB to inspect summary request/auth and before/after mutation counts with a dedicated correctness fixture. Do not mutate the timed benchmark fixture. Record baseline diagnostics separately and preserve all uncommitted changes.

### Task 3: Isolated before/after evidence and final review

**Files:** BE `scripts/load-tests/candidate-review/` scripts/README and report; external evidence directory `../../evidence/` relative to backend worktree. Reuse analysis-progress/list capture, resource and manifest patterns with dedicated guards.

**Interfaces:** before is the saved baseline JAR/FE artifact, after is Task 1/2 artifacts. Fixture manifest records exact IDs/rows/JSON bytes/hash. Public request samples remain identical except the intended size=1 world-list→summary replacement.

- [x] **1. Preserve baseline artifacts before edits affect builds.** Record baseline commit, artifact hashes, runtime and dedicated ports. Seed deterministic synthetic fixture and verify second seed yields the same relevant data hashes. Do not expose runtime auth credentials in evidence.
- [x] **2. Measure baseline in isolation.** Collect warmup then at least30 timed requests each for character pending list size20/includeLegacyfalse, world pending list, and world unfiltered size1; collect real browser initial request accounting. Record median/p95/errors/bytes and observed resource samples. Pause competing builds/tests during timing.
- [x] **3. Measure after with the same fixture and settings.** Add summary replacement series, preserving comparable list series. Compare full list data JSON and summary versus before list metadata, excluding only envelope timestamps. Read back hashes/row counts before/after; report exact mismatch or failure instead of dropping samples.
- [x] **4. Capture actual SQL separately from timing.** Save request-scoped SELECT/binds/count and query plans on the local synthetic DB. Verify no three heavy Job JSON columns across all optimized endpoint paths, including source-book and comparison batch branches. Report candidate full-scan/grouping remains in scope where it still exists.
- [x] **5. Astra final review and evidence report.** Review spec invariants, actual-service SQL, predicate parity, FE generation and invalidation, benchmark method/results and mutation regression results. Resolve actionable issues, rerun affected checks, document unresolved baseline limitations honestly. Stop only owned local processes, retain reproducible artifacts and uncommitted diffs. No commit/push/production action.

Self-review: every spec section maps to Task 1 (read boundary/API/parity), Task 2 (UI/cache), or Task 3 (measurement/equality). Public names and field types are shared through the generated schema; all five Review Focus conditions have explicit tests above.

Completion: all three tasks and final Astra review completed. The old world size1 baseline stopped on its first warmup OOM (zero measured samples); this protocol outcome is retained instead of repeating OOM to fill the sample count. Frontend full-suite failures and unchanged targeted reruns are recorded explicitly. Implementation/evidence remain uncommitted; no integration or deployment was requested for this goal.
