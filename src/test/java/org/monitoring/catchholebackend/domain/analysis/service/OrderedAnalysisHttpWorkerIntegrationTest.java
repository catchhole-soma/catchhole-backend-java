package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual Spring HTTP + Python Worker/SQLAlchemy, with deterministic free LLM/S3 only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "internal.api-key=gh180-e2e-test-only", "server.address=127.0.0.1"
})
@ActiveProfiles("test")
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH180_E2E_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh180_e2e_test")
class OrderedAnalysisHttpWorkerIntegrationTest {
    private static String sourceText(int episodeNo) {
        return sourceText(episodeNo, false);
    }

    private static String sourceText(int episodeNo, boolean automaticIsolation) {
        return sourceText(episodeNo, automaticIsolation, false);
    }

    private static String sourceText(int episodeNo, boolean automaticIsolation, boolean scopeReview) {
        return (episodeNo == 1 ? "세룸이 등장했다. " : "")
                + (episodeNo % 2 == 1 ? "세룸은 오른발을 다쳤다." : "세룸은 완전히 회복했다.")
                + (automaticIsolation && episodeNo == 1 ? " 세룸의 본명은 세룸 로안이다." : "")
                + (automaticIsolation && episodeNo == 1
                    ? " 미궁의 구조는 복잡하다. 미궁의 출입는 입구를 통한다. 미궁의 주기는 매달 열린다. 미궁의 괴물는 늑대가 산다."
                    : "")
                + " 백탑의 색상은 색" + episodeNo + "로 바뀌었다."
                + (episodeNo == 1 ? " 백탑은 색1로 빛났다." : "")
                + (scopeReview && episodeNo == 1 ? " 미궁 1층의 수정은 주변을 밝힌다." : "")
                + (scopeReview && episodeNo == 2 ? " 미궁 외곽 지역은 수정이 줄어들어 어둡다." : "");
    }

    private static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        assertThat(System.getenv("GH180_E2E_JDBC_URL")).doesNotContain(":35432/");
        properties.add("spring.datasource.url", () -> System.getenv("GH180_E2E_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh180");
        properties.add("spring.datasource.password", () -> "gh180-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisRunStateService states;
    @Autowired AnalysisJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateService worldReviews;

    private List<UUID> createRun(int count) {
        return createRun(count, false);
    }

    private List<UUID> createRun(int count, boolean automaticIsolation) {
        return createRun(count, automaticIsolation, false);
    }

    private List<UUID> createRun(int count, boolean automaticIsolation, boolean scopeReview) {
        return createRun(count, automaticIsolation, scopeReview, false);
    }

    private List<UUID> createRun(int count, boolean automaticIsolation, boolean scopeReview, boolean preparationFailure) {
        jdbc.execute("TRUNCATE TABLE works, members CASCADE");
        jdbc.execute("""
                INSERT INTO character_setting_schemas
                (id, work_id, schema_key, attribute_pattern, display_name, fact_type, value_type,
                 value_semantics, merge_policy, aliases_json, source, enabled, created_at, updated_at)
                VALUES (gen_random_uuid(), NULL, 'statuses.condition', 'status.*', '상태', 'STATUS', 'JSON',
                        'BASE_VALUE', 'UPSERT_BY_NAME', '[]'::jsonb, 'SYSTEM_SEED', TRUE, localtimestamp, localtimestamp)
                """);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        return tx.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Member member = Member.register(suffix + "@test.invalid", "test-only", "01012345678", "통합검증");
            entities.persist(member);
            Work work = Work.create(member, "무료 HTTP 통합검증", WorkGenre.FANTASY, "test");
            entities.persist(work);
            UploadBatch batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE);
            entities.persist(batch);
            List<AnalysisJob> created = new ArrayList<>();
            for (int number = 1; number <= count; number++) {
                String source = sourceText(number, automaticIsolation, scopeReview);
                if (preparationFailure && number == 1) {
                    source += " 그 사람은 왼팔을 다쳤다. 미궁의 구조는 여러 갈래다.\n" + "고요".repeat(3500);
                }
                Episode episode = Episode.create(work, null, number, "테스트 회차", "gh180-e2e/" + number,
                        "v1", hash(source), source.length());
                entities.persist(episode);
                AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
                if (automaticIsolation || scopeReview) job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
                created.add(job);
            }
            states.initializeRun(created);
            return created.stream().map(AnalysisJob::getId).toList();
        });
    }

    private String runHarness(int count, String mode) throws Exception {
        Path ai = Path.of(System.getenv().getOrDefault("GH180_AI_ROOT", "../catchhole-backend-ai"))
                .toAbsolutePath().normalize();
        Path python = Path.of(System.getenv().getOrDefault("GH180_PYTHON", "../catchhole-backend-ai/.venv/bin/python"))
                .toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(ai.resolve("tests/ordered_analysis_http_harness.py"))).isTrue();
        String database = System.getenv("GH180_E2E_JDBC_URL").replace("jdbc:postgresql://",
                "postgresql+psycopg://gh180:gh180-test-only@");
        Path output = Path.of("build/reports/tests/ordered-http-worker-" + mode + ".log");
        Files.createDirectories(output.getParent());
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "tests/ordered_analysis_http_harness.py",
                "http://127.0.0.1:" + port, database, String.valueOf(count), mode);
        builder.directory(ai.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().put("PYTHONPATH", ai.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        if (System.getenv("TIKTOKEN_CACHE_DIR") != null) {
            builder.environment().put("TIKTOKEN_CACHE_DIR", System.getenv("TIKTOKEN_CACHE_DIR"));
        }
        Process process = builder.start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        String logs = Files.readString(output);
        assertThat(exited).as(logs).isTrue();
        assertThat(process.exitValue()).as(logs).isZero();
        return logs;
    }

    @Test
    @DisplayName("실제 HTTP 10회차는 다중 원본·임시 상태를 보존하고 Python이 Java 기록을 같은 hash로 복원한다")
    void tenEpisodesCarryTemporaryIdentitiesAddRemoveAndWorldUpdatesAcrossRealHttp() throws Exception {
        List<UUID> ids = createRun(10);
        String logs = runHarness(10, "normal");
        assertThat(logs).contains("\"completed\": 10");
        assertThat(logs).contains("\"javaSealedJournalsReplayed\": 10", "\"typedProjectionParity\": true");
        List<String> firstWorldSources = jdbc.queryForList(
                "SELECT id::text FROM world_setting_candidates WHERE analysis_job_id=? ORDER BY id",
                String.class, ids.getFirst());
        assertThat(firstWorldSources).hasSize(2);
        assertThat(jdbc.queryForList("SELECT evidence_spans->0->>'quote' FROM world_setting_candidates WHERE analysis_job_id=?",
                String.class, ids.getFirst())).containsExactlyInAnyOrder(
                        "백탑의 색상은 색1로 바뀌었다.", "백탑은 색1로 빛났다.");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT comparison_decision_id) FROM world_setting_candidates WHERE analysis_job_id=?",
                Integer.class, ids.getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates WHERE analysis_job_id=? AND comparison_decision_id IS NOT NULL",
                Integer.class, ids.getFirst())).isEqualTo(2);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            var changes = jobs.findById(ids.getFirst()).orElseThrow().getStateJournal().path("changes");
            var worldDecisions = java.util.stream.StreamSupport.stream(changes.spliterator(), false)
                    .filter(change -> change.path("eventId").asText().startsWith("world-decision:")).toList();
            assertThat(worldDecisions).hasSize(1);
            var recordedSources = java.util.stream.StreamSupport.stream(
                    worldDecisions.getFirst().path("sourceCandidateIds").spliterator(), false)
                    .map(com.fasterxml.jackson.databind.JsonNode::asText).toList();
            assertThat(recordedSources).containsExactlyInAnyOrderElementsOf(firstWorldSources);
            assertThat(jobs.findAllById(ids)).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
            });
            var last = states.getProjectedState(jobs.findById(ids.getLast()).orElseThrow());
            assertThat(last.path("characters").size()).isEqualTo(1);
            assertThat(last.path("characters").elements().next().path("slots").size()).isZero();
            assertThat(last.path("worldSettings").size()).isEqualTo(1);
            assertThat(last.path("worldSettings").toString()).contains("색10");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM characters", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_facts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_settings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("캐릭터 선검증 실패는 후보 실패를 commit하고 세계관 호출과 다음 회차를 차단한다")
    void invalidOrderedCandidateCommitsFailureAndStopsBeforeWorldProvider() throws Exception {
        List<UUID> ids = createRun(2);
        String logs = runHarness(2, "prevalidation-failure");
        assertThat(logs).contains("\"expectedPrevalidationFailure\": true", "\"worldProviderCalls\": 0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM setting_candidates WHERE candidate_kind='SETTING' AND comparison_status='FAILED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates", Integer.class)).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findById(ids.getFirst()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
            assertThat(jobs.findById(ids.getLast()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        });
    }

    @Test
    @DisplayName("세계관 입력 상한 초과는 후보·batch 실패를 commit하고 비교 호출과 다음 회차를 차단한다")
    void oversizedWorldBatchCommitsFailureAndStopsBeforeComparisonAndNextEpisode() throws Exception {
        List<UUID> ids = createRun(2);
        String logs = runHarness(2, "world-input-limit");
        assertThat(logs).contains("\"expectedWorldInputLimitFailure\": true", "\"worldComparisonProviderCalls\": 0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates WHERE comparison_status='FAILED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_comparison_batches WHERE status='FAILED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_comparison_decisions", Integer.class)).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findById(ids.getFirst()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
            assertThat(jobs.findById(ids.getLast()).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
        });
    }

    @Test
    @DisplayName("대상 연결 실패를 실제 HTTP와 DB에서 보류하고 20회차 연속 분석과 이후 사용자 확정까지 완료한다")
    void automaticPreparationFailuresPreserveContinuityAndCanBeReviewedLater() throws Exception {
        List<UUID> ids = createRun(20, true, false, true);
        String logs = runHarness(20, "automatic-preparation-failure");
        assertThat(logs).contains("\"completed\": 20", "\"automaticPreparationFailureIsolated\": true");
        for (String table : List.of("setting_candidates", "world_setting_candidates")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE analysis_job_id=?"
                    + " AND preparation_failure_stage='SUBJECT_RESOLUTION' AND comparison_status='FAILED'"
                    + " AND review_status='PENDING_REVIEW' AND automatic_review_hold_reason='SUBJECT_RESOLUTION_FAILED'",
                    Integer.class, ids.getFirst())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM setting_candidates WHERE preparation_failure_stage='SUBJECT_RESOLUTION'"
                + " AND (matched_character_id IS NOT NULL OR provisional_subject_key IS NOT NULL)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates WHERE preparation_failure_stage='SUBJECT_RESOLUTION'"
                + " AND subject_resolution_type='FAILED' AND target_world_setting_id IS NULL"
                + " AND resolved_target_world_setting_ids='[]'::jsonb AND resolved_provisional_subject_keys='[]'::jsonb",
                Integer.class)).isEqualTo(1);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findAllById(ids)).allSatisfy(saved -> {
                assertThat(saved.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(saved.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
                assertThat(saved.getAutomaticAppliedAt()).isNotNull();
            });
            var input = jobs.findById(ids.get(1)).orElseThrow().getAutomaticInputState();
            assertThat(input.path("references").toString()).contains("미궁", "왼팔", "UNCONFIRMED");
            assertThat(input.path("characters").toString()).contains("세룸", "CONFIRMED");
            assertThat(input.path("worldSettings").toString()).contains("백탑", "색1").doesNotContain("미궁");
        });
        assertThat(jdbc.queryForObject("SELECT properties_json->>'색상' FROM world_settings WHERE subject_name='백탑'", String.class))
                .isEqualTo("색20");

        record ReviewTarget(Long owner, UUID work, UUID batch, UUID candidate,
                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory category) { }
        UUID candidateId = jdbc.queryForObject("SELECT id FROM world_setting_candidates WHERE preparation_failure_stage='SUBJECT_RESOLUTION'", UUID.class);
        ReviewTarget review = new TransactionTemplate(transactions).execute(status -> {
            WorldSettingCandidate failed = entities.find(WorldSettingCandidate.class, candidateId);
            return new ReviewTarget(failed.getWork().getMember().getId(), failed.getWork().getId(),
                    failed.getAnalysisJob().getBatch().getId(), candidateId, failed.getCategory());
        });
        worldReviews.updateCandidateDecisions(review.owner(), review.work(),
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest(review.batch(), List.of(
                        new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateItem(review.candidate(),
                                org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD,
                                review.category(), "미궁", null, "구조", "여러 갈래다.", null))));
        worldReviews.confirmCandidate(review.owner(), review.work(), review.candidate(),
                new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest(
                        org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation.ADD,
                        review.category(), "미궁", null, "구조", "여러 갈래다.", false, null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates WHERE id=?"
                + " AND comparison_status='FAILED' AND preparation_failure_stage='SUBJECT_RESOLUTION'"
                + " AND subject_resolution_type='FAILED' AND review_status='CONFIRMED' AND target_world_setting_id IS NOT NULL",
                Integer.class, candidateId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT properties_json->>'구조' FROM world_settings WHERE subject_name='미궁'", String.class))
                .isEqualTo("여러 갈래다.");
    }

    @Test
    @DisplayName("자동 분석은 실제 HTTP로 세계관 네 후보 실패를 보존하고 다음 비교와 회차의 확정 입력까지 이어간다")
    void automaticWorldBatchFailureContinuesRealHttpAndRefreshesSavedState() throws Exception {
        List<UUID> ids = createRun(2, true);
        String logs = runHarness(2, "automatic-world-failure-isolation");
        assertThat(logs).contains("\"completed\": 2", "\"automaticWorldFailureIsolated\": true",
                "\"failedBatchPreservedSourceCount\": 4", "\"realHttpCompleteThenNextClaimAndComplete\": true",
                "\"nextEpisodeReadConfirmedState\": true");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE subject_name='미궁'
                AND comparison_status='FAILED' AND review_status='PENDING_REVIEW'
                AND reviewed_automatically=false AND comparison_decision_id IS NULL
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("""
                SELECT comparison_diagnostics::text FROM world_setting_candidates WHERE subject_name='미궁'
                """, String.class)).hasSize(4).allSatisfy(diagnostics -> assertThat(diagnostics).contains("CANONICAL_TARGET_REQUIRED"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_comparison_batches WHERE canonical_subject_name='미궁' AND status='COMPLETED'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE subject_name='백탑'
                AND comparison_status='COMPLETED' AND review_status='CONFIRMED' AND reviewed_automatically=true
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_settings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT properties_json->>'색상' FROM world_settings WHERE subject_name='백탑'",
                String.class)).isEqualTo("색2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM characters", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_facts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT entity_name FROM setting_candidates WHERE analysis_job_id=?
                AND candidate_kind='CHARACTER_DISCOVERY' AND review_status='CONFIRMED' AND reviewed_automatically=true
                """, String.class, ids.getFirst())).containsExactlyInAnyOrder("세룸", "세룸 로안");
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT matched_character_id) FROM setting_candidates WHERE analysis_job_id=?
                AND candidate_kind='CHARACTER_DISCOVERY'
                """, Integer.class, ids.getFirst())).isEqualTo(1);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findAllById(ids)).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
                assertThat(job.getAutomaticAppliedAt()).isNotNull();
            });
            var secondInput = jobs.findById(ids.getLast()).orElseThrow().getAutomaticInputState();
            assertThat(secondInput.path("characters").toString()).contains("actualCharacterId", "CONFIRMED", "AUTOMATIC", "status.부상");
            assertThat(secondInput.path("characters").elements().next().path("aliases").toString()).contains("세룸 로안");
            assertThat(secondInput.path("characters").elements().next().path("identityEvidence").toString())
                    .contains("세룸의 본명은 세룸 로안이다.");
            assertThat(secondInput.path("worldSettings").toString()).contains("actualWorldSettingId", "백탑", "색1", "CONFIRMED", "AUTOMATIC")
                    .doesNotContain("미궁");
            assertThat(secondInput.path("references").toString()).contains("미궁");
            var failed = entities.createQuery("select c from WorldSettingCandidate c where c.subjectName = '미궁'",
                    WorldSettingCandidate.class).getResultList();
            assertThat(failed).hasSize(4).allSatisfy(candidate -> {
                assertThat(candidate.isManualReviewAvailable()).isTrue();
                assertThat(candidate.getEvidenceSpans()).isNotNull();
            });
        });
    }

    @Test
    @DisplayName("실제 HTTP 분리 복구가 미궁의 정상 속성 셋과 독립 설정을 저장하고 실패 하나만 남겨 세 회차를 완료한다")
    void automaticWorldPartialRecoveryPreservesFailedSourceAndAdvancesThreeEpisodes() throws Exception {
        List<UUID> ids = createRun(3, true);
        String logs = runHarness(3, "automatic-world-partial-recovery");
        assertThat(logs).contains("\"completed\": 3", "\"automaticWorldPartialRecovery\": true",
                "\"failedBatchPreservedSourceCount\": 1", "\"realHttpCompleteThenNextClaimAndComplete\": true",
                "\"nextEpisodeReadConfirmedState\": true");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE subject_name='미궁'
                AND setting_name IN ('구조','출입','주기') AND comparison_status='COMPLETED'
                AND suggested_operation='ADD' AND review_status='CONFIRMED' AND reviewed_automatically=true
                AND comparison_decision_id IS NOT NULL
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE subject_name='미궁'
                AND comparison_status='COMPLETED' AND jsonb_array_length(comparison_diagnostics)>0
                """, Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE subject_name='미궁' AND setting_name='괴물'
                AND comparison_status='FAILED' AND review_status='PENDING_REVIEW'
                AND reviewed_automatically=false AND comparison_decision_id IS NULL
                AND jsonb_array_length(comparison_diagnostics)>0
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates WHERE comparison_status='FAILED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_settings", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT properties_json::text FROM world_settings WHERE subject_name='미궁'", String.class))
                .contains("구조", "복잡하다", "출입", "입구를 통한다", "주기", "매달 열린다").doesNotContain("괴물", "늑대가 산다");
        assertThat(jdbc.queryForObject("SELECT properties_json->>'색상' FROM world_settings WHERE subject_name='백탑'", String.class)).isEqualTo("색3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM characters", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_facts", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_comparison_decision_sources s
                JOIN world_setting_candidates c ON c.id=s.candidate_id
                WHERE c.analysis_job_id=? AND c.subject_name='미궁'
                """, Integer.class, ids.getFirst())).isEqualTo(3);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findAllById(ids)).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
                assertThat(job.getAutomaticAppliedAt()).isNotNull();
            });
            var sources = entities.createQuery("select c from WorldSettingCandidate c where c.subjectName='미궁'", WorldSettingCandidate.class).getResultList();
            assertThat(sources).hasSize(4);
            var covered = jobs.findById(ids.getFirst()).orElseThrow().getStateJournal().path("changes");
            List<String> coveredIds = new ArrayList<>();
            covered.forEach(change -> change.path("sourceCandidateIds").forEach(id -> coveredIds.add(id.asText())));
            assertThat(coveredIds).containsAll(sources.stream().map(source -> source.getId().toString()).toList());
            for (UUID jobId : ids.subList(1, 3)) {
                var input = jobs.findById(jobId).orElseThrow().getAutomaticInputState();
                assertThat(input.path("worldSettings").toString()).contains("미궁", "구조", "출입", "주기", "actualWorldSettingId", "AUTOMATIC")
                        .doesNotContain("괴물", "늑대가 산다");
                assertThat(input.path("references").size()).isEqualTo(1);
                assertThat(input.path("references").elements().next().path("settingName").asText()).isEqualTo("괴물");
                assertThat(input.path("characters").toString()).contains("actualCharacterId", "세룸 로안");
                var slots = input.path("characters").elements().next().path("slots");
                if (jobId.equals(ids.get(1))) assertThat(slots.toString()).contains("status.부상");
                else assertThat(slots.isEmpty()).isTrue();
            }
        });
    }

    @Test
    @DisplayName("캐릭터 비교 일부 실패는 정상 설정을 확정하고 실패 근거만 참고로 남겨 다음 회차까지 완료한다")
    void automaticCharacterPartialRecoveryConfirmsOnlyValidDecisionAcrossRealHttp() throws Exception {
        List<UUID> ids = createRun(2, true);
        String logs = runHarness(2, "automatic-character-partial-recovery");
        assertThat(logs).contains("\"completed\": 2", "\"automaticCharacterPartialRecovery\": true",
                "\"independentDecisionPreserved\": true", "\"failedSourcePreservedAsReference\": true",
                "\"nextEpisodeReadConfirmedState\": true");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM setting_candidates WHERE analysis_job_id=?
                AND attribute_name='status.부상' AND comparison_status='COMPLETED'
                AND review_status='CONFIRMED' AND reviewed_automatically=true
                """, Integer.class, ids.getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM setting_candidates WHERE analysis_job_id=?
                AND attribute_name='status.오른발_부상' AND comparison_status='FAILED'
                AND review_status='PENDING_REVIEW' AND reviewed_automatically=false
                AND jsonb_array_length(evidence_spans)>0
                """, Integer.class, ids.getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_facts WHERE fact_key='status.오른발_부상'",
                Integer.class)).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findAllById(ids)).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
                assertThat(job.getAutomaticAppliedAt()).isNotNull();
            });
            var input = jobs.findById(ids.getLast()).orElseThrow().getAutomaticInputState();
            assertThat(input.path("characters").toString()).contains("status.부상", "CONFIRMED", "AUTOMATIC")
                    .doesNotContain("status.오른발_부상");
            var references = input.path("references");
            assertThat(references.size()).isEqualTo(1);
            assertThat(references.elements().next().path("settingName").asText()).isEqualTo("status.오른발_부상");
        });
    }

    @Test
    @DisplayName("일반 검토가 실제 Python과 HTTP를 통해 저장되고 원본 주체를 보존하며 다음 회차가 계속된다")
    void generalUncertaintyPreservesOriginalSubjectAndContinuesRealHttp() throws Exception {
        List<UUID> ids = createRun(2, true);
        var schemaResponse = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(schemaResponse.statusCode()).isEqualTo(200);
        assertThat(schemaResponse.body()).contains("GENERAL_UNCERTAINTY");
        Files.writeString(Path.of("build/reports/tests/general-uncertainty-openapi.json"), schemaResponse.body());
        String logs = runHarness(2, "automatic-general-uncertainty");
        assertThat(logs).contains("\"completed\": 2", "\"automaticGeneralUncertaintyPreserved\": true",
                "\"originalSubjectPreservedInNextReference\": true", "\"nextEpisodeReadConfirmedState\": true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_setting_candidates WHERE comparison_status='FAILED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE analysis_job_id=?
                AND subject_name='미궁' AND setting_name='구조' AND extracted_value='복잡하다.'
                AND proposed_value='복잡하다.' AND comparison_status='COMPLETED'
                AND comparison_review_reason='GENERAL_UNCERTAINTY' AND suggested_operation='REVIEW_REQUIRED'
                AND review_status='PENDING_REVIEW' AND reviewed_automatically=false
                """, Integer.class, ids.getFirst())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM world_settings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT properties_json->>'색상' FROM world_settings WHERE subject_name='백탑'", String.class)).isEqualTo("색2");
        assertThat(jdbc.queryForObject("SELECT properties_json ? '구조' FROM world_settings WHERE subject_name='백탑'", Boolean.class)).isFalse();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findAllById(ids)).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
                assertThat(job.getAutomaticAppliedAt()).isNotNull();
            });
            var references = jobs.findById(ids.getLast()).orElseThrow().getAutomaticInputState().path("references");
            assertThat(references.size()).isEqualTo(1);
            var reference = references.elements().next();
            assertThat(reference.path("subjectName").asText()).isEqualTo("미궁");
            assertThat(reference.path("value").asText()).isEqualTo("복잡하다.");
            assertThat(reference.path("comparisonReviewReason").asText()).isEqualTo("GENERAL_UNCERTAINTY");
            assertThat(reference.path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        });
    }

    @Test
    @DisplayName("범위가 다른 비교는 실제 HTTP 재시도 후 검토로 저장되고 다음 회차의 확정 설정을 덮어쓰지 않는다")
    void scopeMismatchReviewPreservesBothPathsAndContinuesAutomaticRealHttp() throws Exception {
        List<UUID> ids = createRun(3, false, true);
        String logs = runHarness(3, "automatic-scope-mismatch");
        var schemaResponse = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(schemaResponse.statusCode()).isEqualTo(200);
        assertThat(schemaResponse.body()).contains("SCOPE_MISMATCH");
        Files.writeString(Path.of("build/reports/tests/scope-mismatch-openapi.json"), schemaResponse.body());
        assertThat(logs).contains("\"completed\": 3", "\"scopeMismatchReviewPreserved\": true",
                "\"specificRetryThenReview\": true", "\"nextEpisodeReadReviewReference\": true");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates
                WHERE comparison_status='FAILED'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM world_setting_candidates WHERE analysis_job_id=?
                AND subject_name='미궁' AND scope_name='외곽 지역' AND setting_name='조명 환경'
                AND comparison_status='COMPLETED' AND suggested_operation='REVIEW_REQUIRED'
                AND comparison_review_reason='SCOPE_MISMATCH' AND review_status='PENDING_REVIEW'
                AND reviewed_automatically=false AND matched_scope_name='1층' AND matched_property_name='광원'
                AND proposed_scope_name='외곽 지역' AND proposed_setting_name='조명 환경'
                AND target_world_setting_id IS NOT NULL
                """, Integer.class, ids.get(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT before_value FROM world_setting_candidates
                WHERE analysis_job_id=? AND subject_name='미궁'
                """, String.class, ids.get(1))).isEqualTo("미궁 1층의 수정은 주변을 밝힌다.");
        assertThat(jdbc.queryForObject("""
                SELECT evidence_spans->0->>'quote' FROM world_setting_candidates
                WHERE analysis_job_id=? AND subject_name='미궁'
                """, String.class, ids.get(1))).isEqualTo("미궁 외곽 지역은 수정이 줄어들어 어둡다.");
        assertThat(jdbc.queryForObject("""
                SELECT properties_json->'1층'->>'광원' FROM world_settings WHERE subject_name='미궁'
                """, String.class)).isEqualTo("미궁 1층의 수정은 주변을 밝힌다.");
        assertThat(jdbc.queryForObject("""
                SELECT properties_json ? '외곽 지역' FROM world_settings WHERE subject_name='미궁'
                """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT properties_json->>'색상' FROM world_settings WHERE subject_name='백탑'",
                String.class)).isEqualTo("색3");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(jobs.findAllById(ids)).allSatisfy(job -> {
                assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
                assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.SEALED);
                assertThat(job.getAutomaticAppliedAt()).isNotNull();
            });
            var thirdInput = jobs.findById(ids.getLast()).orElseThrow().getAutomaticInputState();
            assertThat(thirdInput.path("worldSettings").toString()).contains("미궁 1층의 수정은 주변을 밝힌다.")
                    .doesNotContain("미궁 외곽 지역은 수정이 줄어들어 어둡다.");
            var references = thirdInput.path("references");
            assertThat(references.size()).isEqualTo(1);
            var reference = references.iterator().next();
            assertThat(reference.path("reason").asText()).isEqualTo("원문과 기존 설정의 적용 범위가 달라 확인이 필요한 미확정 참고 정보입니다.");
            assertThat(reference.path("scopeName").asText()).isEqualTo("외곽 지역");
            assertThat(reference.path("settingName").asText()).isEqualTo("조명 환경");
            assertThat(reference.path("matchedScopeName").asText()).isEqualTo("1층");
            assertThat(reference.path("matchedPropertyName").asText()).isEqualTo("광원");
            assertThat(reference.path("confirmationStatus").asText()).isEqualTo("UNCONFIRMED");
        });
    }

}
