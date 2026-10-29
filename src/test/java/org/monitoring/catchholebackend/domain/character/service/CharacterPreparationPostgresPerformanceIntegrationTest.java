package org.monitoring.catchholebackend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingMergePolicy;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingSchemaSource;
import org.monitoring.catchholebackend.domain.character.type.CharacterSettingValueSemantics;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingWorkerService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Synthetic state only; actual PostgreSQL, Flyway, transactions and HTTP. No model/provider calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "internal.api-key=synthetic-gh294-key",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.monitoring.catchholebackend.domain.character.service.CharacterPreparationPostgresPerformanceIntegrationTest$SqlCapture"
})
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("대규모 누적 상태의 캐릭터 준비·claim PostgreSQL 성능 경계")
class CharacterPreparationPostgresPerformanceIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "stats.gh294power";
    private static final String QUOTE = "합성 인물의 수치는 35다.";
    private static final int LARGE_CANDIDATES = 535;
    private static final int MINIMUM_INPUT_BYTES = 1_200_000;
    private static final boolean BASELINE = "true".equals(System.getenv("GH294_MEASURE_BASELINE"));

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh294_performance_test").withEnv("POSTGRES_USER", "gh294_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-gh294-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/gh294_performance_test");
        p.add("spring.datasource.username", () -> "gh294_test");
        p.add("spring.datasource.password", () -> "disposable-gh294-test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisRunStateService states;
    @Autowired AnalysisJobLeaseService leases;
    @Autowired CharacterAnalysisStateService preparation;
    @Autowired CharacterAnalysisStateMapper stateMapper;
    @Autowired AnalysisStateJournal journal;
    @Autowired WorldSettingWorkerService worlds;

    /** Enabled only around the measured operation; setup and correctness readback are excluded. */
    public static class SqlCapture implements StatementInspector {
        private static final List<String> selects = new CopyOnWriteArrayList<>();
        private static volatile boolean enabled;
        @Override public String inspect(String sql) {
            if (enabled && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) {
                selects.add(sql.toLowerCase(Locale.ROOT));
            }
            return sql;
        }
        static void start() { selects.clear(); enabled = true; }
        static Counts stop() {
            enabled = false;
            long runs = selects.stream().filter(sql -> sql.contains(" from analysis_jobs ")
                    && where(sql).contains("analysis_run_id") && where(sql).contains("run_generation")).count();
            long anchors = selects.stream().filter(sql -> sql.contains(" from setting_candidates ")
                    && where(sql).matches(".*\\.id=\\? and .*\\.work_id=\\?.*")).count();
            long schemas = selects.stream().filter(sql -> sql.contains(" from character_setting_schemas ")).count();
            return new Counts(selects.size(), runs, anchors, schemas);
        }
        private static String where(String sql) {
            int at = sql.indexOf(" where ");
            return at < 0 ? "" : sql.substring(at);
        }
    }

    @Test @Order(1)
    @DisplayName("같은 준비 중 등록한 임시 인물을 뒤 후보가 보고 다시 준비해도 판단 기록이 늘지 않는다")
    void newlyRegisteredIdentityIsVisibleAndRepeatedPreparationIsIdempotent() throws Exception {
        Fixture fixture = fixture(6, false, false);
        prepare(fixture, "new-identity");
        JsonNode first = tx().execute(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            assertThat(states.getProjectedState(job).path("characters").has(fixture.provisional())).isTrue();
            assertThat(job.getStateJournal().path("changes")).hasSize(1);
            return job.getStateJournal();
        });
        prepare(fixture, "new-identity-repeat");
        tx().executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            assertThat(job.getStateJournal()).isEqualTo(first);
            assertThat(em.createQuery("select c from SettingCandidate c where c.analysisJob.id=:job and c.id<>:anchor", SettingCandidate.class)
                    .setParameter("job", fixture.job()).setParameter("anchor", fixture.anchor()).getResultList())
                    .allSatisfy(candidate -> assertThat(candidate.getComparisonStatus()).isEqualTo(CharacterFactComparisonStatus.PENDING));
        });
        assertFrozenStatePreserved(fixture);
    }

    @Test @Order(2)
    @DisplayName("535개 후보와 1.2MB 누적 이력의 준비는 후보마다 run과 발견 후보를 다시 조회하지 않는다")
    void preparationReusesFrozenStateFor535Candidates() throws Exception {
        Fixture fixture = fixture(LARGE_CANDIDATES, true, true);
        Measurement first = prepare(fixture, "prepare-large");
        Measurement repeated = prepare(fixture, "prepare-large-repeat");
        assertBoundedRepeatedReads(first);
        assertBoundedRepeatedReads(repeated);
        assertFrozenStatePreserved(fixture);
    }

    @Test @Order(3)
    @DisplayName("535개 후보의 실제 HTTP claim은 30초 안에 전체 선택값과 근거를 보존한다")
    void actualHttpClaimPreservesAllCandidatesWithinWorkerBudget() throws Exception {
        Fixture fixture = fixture(LARGE_CANDIDATES, true, true);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/internal/v1/analysis-jobs/" + fixture.job() + "/character-fact-comparison-batches/claim-next"))
                .timeout(Duration.ofSeconds(30)).header("X-Internal-Api-Key", "synthetic-gh294-key")
                .header("X-Worker-Lease-Token", fixture.lease().toString())
                .header("X-Character-Comparison-Person-Batches", "true")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response;
        long started = System.nanoTime();
        SqlCapture.start();
        Counts counts;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            counts = SqlCapture.stop();
        }
        Measurement measured = new Measurement("http-claim-large", fixture.candidates(), fixture.inputBytes(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(), counts);
        report(measured);
        assertThat(response.statusCode()).as("synthetic claim response: %s", response.body()).isEqualTo(200);
        JsonNode payload = MAPPER.readTree(response.body()).path("data");
        assertThat(payload.path("candidates")).hasSize(LARGE_CANDIDATES - 1);
        for (JsonNode candidate : payload.path("candidates")) {
            assertThat(candidate.path("attributeValue").asText()).isEqualTo("35");
            assertThat(candidate.path("valueJson").path("value").asInt()).isEqualTo(35);
            assertThat(candidate.path("evidenceSpans").get(0).path("quote").asText()).isEqualTo(QUOTE);
        }
        assertThat(payload.path("analysisContext").path("inputStateHash").asText()).isEqualTo(fixture.inputHash());
        assertBoundedRepeatedReads(measured);
        if (!BASELINE) assertThat(measured.sql().schemaQueries()).as("claim schema queries").isEqualTo(1);
        assertFrozenStatePreserved(fixture);
    }

    @Test @Order(4)
    @DisplayName("35개 세계관 주체 해소는 큰 입력을 재사용하고 첫 후보의 신규 대상을 뒤 후보도 참조한다")
    void worldResolutionReusesLargeInputAndNewlyRegisteredIdentity() throws Exception {
        Fixture fixture = fixture(1, true, true);
        List<UUID> ids = tx().execute(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            List<UUID> created = new ArrayList<>();
            for (int index = 0; index < 35; index++) {
                WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                        WorldSettingCategory.RACE, "합성 종족", "합성 속성 " + index, "합성 값 " + index,
                        evidence(), BigDecimal.ONE, null);
                em.persist(candidate);
                created.add(candidate.getId());
            }
            return created;
        });
        String key = "provisional-world:" + ids.getFirst();
        var request = new WorkerWorldSettingSubjectResolutionRequest(ids.stream().map(id ->
                new WorkerWorldSettingSubjectResolutionRequest.SubjectResolutionInput(id, List.of(), List.of(key), false)).toList());
        long started = System.nanoTime();
        SqlCapture.start();
        Counts counts;
        org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectResolutionResponse response;
        try {
            response = worlds.resolveWorldSettingSubjects(fixture.job(), fixture.lease(), request);
        } finally {
            counts = SqlCapture.stop();
        }
        Measurement measured = new Measurement("world-resolve-large", ids.size(), fixture.inputBytes(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(), counts);
        report(measured);
        assertThat(response.resolutions()).hasSize(ids.size());
        assertThat(response.resolutions()).allSatisfy(resolved -> assertThat(resolved.canonicalSubjectKey()).isEqualTo(key));
        assertBoundedRepeatedReads(measured);
        tx().executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            JsonNode projected = states.getProjectedState(job);
            assertThat(projected.path("worldSettings").size()).isEqualTo(1);
            assertThat(projected.path("worldSettings").path(key).path("subjectName").asText()).isEqualTo("합성 종족");
        });
        assertFrozenStatePreserved(fixture, 2);
    }

    @Test @Order(5)
    @DisplayName("535개 후보와 57개 누적 이력 snapshot journal도 최종 32개 이력을 보존하며 재사용한다")
    void preparationReusesLargeCumulativeJournalWithoutChangingFinalHistory() throws Exception {
        Fixture fixture = fixture(LARGE_CANDIDATES, false, true);
        JournalFixture recorded = tx().execute(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            List<UUID> sources = em.createQuery("select c.id from SettingCandidate c where c.analysisJob.id=:job and c.id<>:anchor order by c.id", UUID.class)
                    .setParameter("job", fixture.job()).setParameter("anchor", fixture.anchor()).setMaxResults(57).getResultList();
            String characterRef = fixture.frozen().path("characters").fieldNames().next();
            var history = JSON.arrayNode();
            List<AnalysisStateChange> changes = new ArrayList<>();
            String quote = "synthetic-history-evidence-".repeat(10) + "synthetic-source-xx";
            for (int index = 0; index < 57; index++) {
                ObjectNode row = JSON.objectNode().put("factId", UUID.randomUUID().toString())
                        .put("sourceCandidateId", sources.get(index).toString()).put("factType", "STAT")
                        .put("factKey", KEY).put("factValue", "35").put("operation", "HISTORY_ONLY").put("temporalScope", "PAST");
                row.putObject("valueJson").put("value", 35);
                row.putObject("narrativeTime").put("label", "합성 과거 " + (index % 24 + 1));
                row.putObject("supportingSource").put("sourceEpisodeNo", index % 24 + 1)
                        .putArray("evidenceSpans").addObject().put("quote", quote).put("startOffset", 0).put("endOffset", quote.length());
                history.add(row);
                if (history.size() > 32) history.remove(0);
                changes.add(new AnalysisStateChange("synthetic-history:" + index,
                        List.of("characters", characterRef, "historyFacts"), history.deepCopy(), false,
                        "HISTORY_ONLY", List.of(sources.get(index))));
            }
            states.appendValidatedChanges(job, job.getInputStateHash(), changes);
            em.flush();
            int journalBytes = job.getStateJournal().toString().getBytes(StandardCharsets.UTF_8).length;
            assertThat(journalBytes).isBetween(900_000, 1_050_000);
            assertThat(states.getProjectedState(job).path("characters").path(characterRef).path("historyFacts")).hasSize(32);
            return new JournalFixture(characterRef, journal.hash(history), journalBytes);
        });
        Measurement measured = prepare(fixture, "prepare-large-journal", recorded.journalBytes());
        assertBoundedRepeatedReads(measured);
        tx().executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            JsonNode history = states.getProjectedState(job).path("characters").path(recorded.characterRef()).path("historyFacts");
            assertThat(history).hasSize(32);
            assertThat(journal.hash(history)).isEqualTo(recorded.finalHistoryHash());
        });
        assertFrozenStatePreserved(fixture, 58);
    }

    private Measurement prepare(Fixture fixture, String phase) throws Exception {
        return prepare(fixture, phase, 0);
    }

    private Measurement prepare(Fixture fixture, String phase, int journalBytes) throws Exception {
        long started = System.nanoTime();
        SqlCapture.start();
        Counts counts;
        try {
            tx().executeWithoutResult(status -> preparation.prepareProvisionalCandidates(
                    leases.getRunningAnalysisJobForUpdate(fixture.job(), fixture.lease())));
        } finally {
            counts = SqlCapture.stop();
        }
        Measurement measured = new Measurement(phase, fixture.candidates(), fixture.inputBytes(), journalBytes,
                Duration.ofNanos(System.nanoTime() - started).toMillis(), counts);
        report(measured);
        return measured;
    }

    private void assertBoundedRepeatedReads(Measurement measured) {
        // Explicit baseline mode measures the unchanged implementation; CI/default runs enforce the fix.
        if (BASELINE) return;
        assertThat(measured.sql().runQueries()).as("run-chain queries: %s", measured).isLessThanOrEqualTo(4);
        assertThat(measured.sql().anchorQueries()).as("per-ID anchor queries: %s", measured).isLessThanOrEqualTo(2);
    }

    private Fixture fixture(int candidateCount, boolean largeInput, boolean existingIdentity) {
        return tx().execute(status -> {
            String unique = UUID.randomUUID().toString();
            Member member = Member.register("gh294-" + unique + "@example.invalid", "test-only", null, "합성 작가");
            em.persist(member);
            Work work = Work.create(member, "GH294 합성 작품", WorkGenre.FANTASY, "운영 자료 없음");
            em.persist(work);
            em.persist(CharacterSettingSchema.create(work, KEY, null, "합성 능력치", CharacterFactType.STAT,
                    SettingValueType.NUMBER, CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.REPLACE,
                    JSON.arrayNode(), CharacterSettingSchemaSource.DEV_SEED, true));
            List<AnalysisJob> run = new ArrayList<>();
            for (int number = 1; number <= 25; number++) {
                Episode episode = Episode.create(work, null, number, "합성 회차", "gh294/" + unique + "/" + number,
                        "synthetic-v1", "a".repeat(64), 200);
                em.persist(episode);
                AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
                job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
                run.add(job);
            }
            states.initializeRun(run);
            for (AnalysisJob predecessor : run.subList(0, 24)) {
                // Build valid sealed predecessor envelopes without any paid extraction/model calls.
                predecessor.claim("synthetic", "합성 선행 회차", LocalDateTime.now().plusMinutes(10));
                assertThat(states.prepareInput(predecessor)).isTrue();
                states.seal(predecessor);
                predecessor.completeAutomaticApplication();
                predecessor.succeed("{}", 0, 0);
            }
            AnalysisJob job = run.getLast();
            UUID lease = job.claim("synthetic", "캐릭터 준비 검증", LocalDateTime.now().plusMinutes(10));
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED);
            WorkCharacter previous = WorkCharacter.create(work, "이전 회차 인물", null, null, null,
                    JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), JSON.objectNode(),
                    run.getFirst().getEpisode().getId());
            em.persist(previous);
            ObjectNode frozen = journal.emptyState();
            // A nonmatching active policy exercises request-local anchor lookup reuse.
            frozen.withObject("references").putObject("human-rejection:synthetic")
                    .put("kind", "HUMAN_REJECTION_POLICY").put("policy", "EXACT_SOURCE_CLAIM_V1")
                    .put("domain", "characters").put("fingerprint", "f".repeat(64));
            ObjectNode historical = stateMapper.toState(previous, Map.of(), Map.of());
            if (largeInput) addHistory(historical);
            ((ObjectNode) frozen.path("characters")).set(CharacterAnalysisStateMapper.persistedRef(previous.getId()), historical);
            job.captureAutomaticInput(frozen);
            assertThat(states.prepareInput(job)).isTrue();
            SettingCandidate anchor = SettingCandidate.createCharacterDiscovery(work, job.getEpisode(), null, job,
                    "합성 인물", "합성 인물", null, SettingCandidateMatchStatus.UNRESOLVED,
                    evidence(), BigDecimal.ONE, JSON.objectNode().put("candidate_kind", "CHARACTER_DISCOVERY")
                            .put("entity_type", "CHARACTER").put("entity_name", "합성 인물"));
            em.persist(anchor);
            String provisional = CharacterAnalysisStateMapper.provisionalRef(anchor.getId());
            ReflectionTestUtils.setField(anchor, "provisionalSubjectKey", provisional);
            if (existingIdentity) {
                states.appendValidatedChanges(job, job.getInputStateHash(), List.of(new AnalysisStateChange(
                        "character-discovery:" + anchor.getId(), List.of("characters", provisional),
                        stateMapper.toProvisionalState(anchor.getId(), anchor.getEntityName(), 25), false,
                        "IDENTITY", List.of(anchor.getId()))));
            }
            for (int index = 1; index < candidateCount; index++) {
                SettingCandidate candidate = SettingCandidate.create(work, job.getEpisode(), null, job,
                        SettingEntityType.CHARACTER, "합성 인물", "합성 인물", null, SettingCandidateMatchStatus.UNRESOLVED,
                        KEY, "35", SettingValueType.NUMBER, JSON.objectNode().put("value", 35), evidence(), BigDecimal.ONE, null);
                ReflectionTestUtils.setField(candidate, "provisionalSubjectKey", provisional);
                em.persist(candidate);
            }
            em.flush();
            int inputBytes = frozen.toString().getBytes(StandardCharsets.UTF_8).length;
            if (largeInput) assertThat(inputBytes).isGreaterThanOrEqualTo(MINIMUM_INPUT_BYTES);
            return new Fixture(job.getId(), lease, anchor.getId(), provisional, candidateCount, inputBytes,
                    job.getInputStateHash(), frozen.deepCopy());
        });
    }

    private void addHistory(ObjectNode character) {
        var history = character.withArray("historyFacts");
        String syntheticEvidence = "synthetic-history-evidence-".repeat(28);
        int bytes = 0;
        for (int index = 0; bytes < MINIMUM_INPUT_BYTES + 10_000; index++) {
            ObjectNode row = JSON.objectNode().put("factId", UUID.randomUUID().toString())
                    .put("factType", "STATUS").put("factKey", "status.synthetic-" + index)
                    .put("factValue", "해제된 합성 상태 " + index).put("operation", "REMOVE").put("temporalScope", "PAST");
            row.putObject("valueJson").put("value", "해제됨");
            row.putObject("narrativeTime").put("label", "합성 과거 " + (index % 24 + 1));
            row.putObject("supportingSource").put("sourceEpisodeNo", index % 24 + 1)
                    .putArray("evidenceSpans").addObject().put("quote", syntheticEvidence).put("startOffset", 0)
                    .put("endOffset", syntheticEvidence.length());
            history.add(row);
            bytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
        }
    }

    private void assertFrozenStatePreserved(Fixture fixture) {
        assertFrozenStatePreserved(fixture, 1);
    }

    private void assertFrozenStatePreserved(Fixture fixture, int journalChanges) {
        tx().executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
            assertThat(job.getInputStateHash()).isEqualTo(fixture.inputHash());
            // JSONB can read an integral LongNode as IntNode; compare full canonical content, not Java node subclasses.
            assertThat(journal.hash(job.getAutomaticInputState())).isEqualTo(journal.hash(fixture.frozen()));
            assertThat(journal.hash(job.getAutomaticInputState())).isEqualTo(fixture.inputHash());
            assertThat(job.getStateJournal().path("changes")).hasSize(journalChanges);
        });
    }

    private static JsonNode evidence() {
        return JSON.arrayNode().add(JSON.objectNode().put("quote", QUOTE).put("startOffset", 0).put("endOffset", QUOTE.length()));
    }

    private static void report(Measurement measured) throws Exception {
        String line = MAPPER.writeValueAsString(measured);
        System.out.println("GH294_PERFORMANCE " + line);
        String directory = System.getenv("GH294_PERFORMANCE_OUTPUT");
        if (directory != null && !directory.isBlank()) {
            Path output = Path.of(directory);
            Files.createDirectories(output);
            Files.writeString(output.resolve(measured.phase() + ".json"), line + "\n", StandardCharsets.UTF_8);
        }
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private record Fixture(UUID job, UUID lease, UUID anchor, String provisional, int candidates, int inputBytes,
            String inputHash, JsonNode frozen) {}
    private record Counts(long selects, long runQueries, long anchorQueries, long schemaQueries) {}
    private record JournalFixture(String characterRef, String finalHistoryHash, int journalBytes) {}
    private record Measurement(String phase, int candidates, int inputBytes, int journalBytes, long elapsedMillis, Counts sql) {
        private Measurement(String phase, int candidates, int inputBytes, long elapsedMillis, Counts sql) {
            this(phase, candidates, inputBytes, 0, elapsedMillis, sql);
        }
    }
}
