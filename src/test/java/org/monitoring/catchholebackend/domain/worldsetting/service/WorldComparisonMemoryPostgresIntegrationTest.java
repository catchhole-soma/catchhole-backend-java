package org.monitoring.catchholebackend.domain.worldsetting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.lang.management.ManagementFactory;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterAnalysisStateMapper;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.support.JdbcResultMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Opt-in, synthetic-only real PostgreSQL + HTTP regression. See scripts/load-tests/comparison-memory. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "internal.api-key=synthetic-gh365-key"})
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "GH365_MEMORY_PROFILE", matches = "true")
@Import(WorldComparisonMemoryPostgresIntegrationTest.MetricsConfiguration.class)
@DisplayName("60개 세계관 claim의 큰 누적 상태·이력 JSON 메모리 회귀")
class WorldComparisonMemoryPostgresIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CANDIDATES = 60;
    private static final int HISTORY_CHANGES = 40;
    private static final int INITIAL_HISTORY = 120;
    private static final int INPUT_BYTES = 1_496_423;
    private static final int JOURNAL_BYTES = 5_723_520;
    private static final String QUOTE = "Synthetic world evidence.";
    private static final boolean BASELINE = Boolean.parseBoolean(System.getenv("GH365_MEASURE_BASELINE"));
    private static final boolean COMPACT = "compact".equals(System.getenv("GH365_JOURNAL_MODE"));

    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh365_memory_test").withEnv("POSTGRES_USER", "gh365_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-gh365-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/gh365_memory_test");
        p.add("spring.datasource.username", () -> "gh365_test");
        p.add("spring.datasource.password", () -> "disposable-gh365-test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }

    @TestConfiguration(proxyBeanMethods = false) static class MetricsConfiguration {
        @Bean static BeanPostProcessor jdbcResultMetrics() { return new JdbcResultMetrics(); }
    }

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisRunStateService states;
    @Autowired CharacterAnalysisStateMapper characters;
    @Autowired AnalysisStateJournal journal;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.monitoring.catchholebackend.global.storage.ObjectStorage objectStorage;

    @Test
    @DisplayName("운영과 같은 768MiB heap에서 60개 후보를 20개씩 빠짐없이 claim하고 이력·근거를 보존한다")
    void worldClaimsPreserveEveryCandidateAndHistoryWithinHeapBudget() throws Exception {
        int samples = Integer.parseInt(System.getenv().getOrDefault("GH365_SAMPLES", "3"));
        assertThat(samples).isBetween(1, 10);
        for (int sample = 1; sample <= samples; sample++) {
            Fixture fixture = fixture(sample);
            // Setup's full-array copies must not remain live in the measured request.
            System.gc();
            var seen = new HashSet<String>();
            ArrayNode semanticResponses = JSON.arrayNode();
            try (HttpClient client = HttpClient.newHttpClient()) {
                for (int batch = 1; batch <= 3; batch++) {
                    String phase = "sample-" + sample + "-claim-" + batch;
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/api/internal/v1/analysis-jobs/" + fixture.job()
                                    + "/world-setting-comparison-batches/claim-next"))
                            .timeout(Duration.ofSeconds(120)).header("X-Internal-Api-Key", "synthetic-gh365-key")
                            .header("X-Worker-Lease-Token", fixture.lease().toString())
                            .POST(HttpRequest.BodyPublishers.noBody()).build();
                    // A pre-request artifact survives even if the baseline JVM exhausts its heap.
                    write(phase + "-started", Map.of("pendingCandidates", CANDIDATES - (batch - 1) * 20,
                            "inputJsonbTextBytes", fixture.inputBytes(), "journalJsonbTextBytes", fixture.journalBytes()));
                    HttpResponse<String> response;
                    RuntimeSample runtime;
                    List<JdbcResultMetrics.QueryResult> queries;
                    JdbcResultMetrics.start();
                    try (RuntimeProbe probe = new RuntimeProbe()) {
                        try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
                        finally { runtime = probe.finish(); queries = JdbcResultMetrics.stop(); }
                    }
                    long repeatedRows = queries.stream().filter(JdbcResultMetrics.QueryResult::candidateQuery)
                            .filter(JdbcResultMetrics.QueryResult::selectsJobJson)
                            .mapToLong(JdbcResultMetrics.QueryResult::consumedRows).sum();
                    write(phase, Map.of("runtime", runtime, "queries", queries, "httpStatus", response.statusCode(),
                            "httpResponseUtf8Bytes", response.body().getBytes(StandardCharsets.UTF_8).length,
                            "candidateRowsSelectingJobJson", repeatedRows,
                            "candidateLogicalJobJsonBytes", repeatedRows * (fixture.inputBytes() + fixture.journalBytes()),
                            "fixture", fixture));
                    assertThat(response.statusCode()).as("synthetic world claim HTTP status").isEqualTo(200);
                    JsonNode payload = MAPPER.readTree(response.body()).path("data");
                    assertThat(payload.path("candidates")).hasSize(20);
                    for (JsonNode candidate : payload.path("candidates")) {
                        assertThat(seen.add(candidate.path("candidateId").asText())).isTrue();
                        assertThat(candidate.path("evidenceSpans").get(0).path("quote").asText()).isEqualTo(QUOTE);
                        ObjectNode semantic = candidate.deepCopy();
                        semantic.remove(List.of("candidateId", "source"));
                        semanticResponses.add(semantic);
                    }
                    assertThat(payload.path("analysisContext").path("inputStateHash").asText()).isEqualTo(fixture.inputHash());
                    if (!BASELINE) assertThat(repeatedRows).as("candidate result rows must not select the parent Job JSON").isZero();
                }
            }
            assertThat(seen).hasSize(CANDIDATES);
            assertThat(jdbc.queryForObject("select count(*) from world_setting_candidates where analysis_job_id=? and comparison_status='PROCESSING'",
                    Integer.class, fixture.job())).isEqualTo(CANDIDATES);
            tx().executeWithoutResult(status -> {
                AnalysisJob job = em.find(AnalysisJob.class, fixture.job());
                assertThat(journal.hash(job.getAutomaticInputState())).isEqualTo(fixture.inputHash());
                assertThat(journal.hash(job.getStateJournal())).isEqualTo(fixture.journalHash());
                assertThat(journal.hash(states.getProjectedState(job).path("characters").path(fixture.characterRef()).path("historyFacts")))
                        .isEqualTo(fixture.finalHistoryHash());
                assertThat(job.getStateJournal().path("changes")).hasSize(HISTORY_CHANGES);
            });
            write("sample-" + sample + "-parity", Map.of("responseSemanticHash", journal.hash(semanticResponses),
                    "finalHistoryAndEvidenceHash", fixture.finalHistoryHash(), "claimedUniqueCandidates", seen.size()));
        }
        org.mockito.Mockito.verifyNoInteractions(objectStorage);
    }

    private Fixture fixture(int sample) {
        return tx().execute(status -> {
            String unique = UUID.randomUUID().toString();
            Member member = Member.register("gh365-" + unique + "@example.invalid", "test-only", null, "Synthetic author");
            em.persist(member);
            Work work = Work.create(member, "GH365 synthetic work", WorkGenre.FANTASY, "No production material");
            em.persist(work);
            String source = QUOTE.repeat(CANDIDATES);
            String sourceHash = new org.monitoring.catchholebackend.global.storage.ObjectStorageService(objectStorage).contentHash(source);
            Episode episode = Episode.create(work, null, 35, "Synthetic episode", "gh365/" + unique,
                    "synthetic-v1", sourceHash, source.length());
            em.persist(episode);
            AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            states.initializeRun(List.of(job));
            UUID lease = job.claim("synthetic", "GH365 memory measurement", LocalDateTime.now().plusMinutes(30));
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
            WorkCharacter previous = WorkCharacter.create(work, "Historical synthetic character", null, null, null,
                    JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), episode.getId());
            em.persist(previous);
            String characterRef = CharacterAnalysisStateMapper.persistedRef(previous.getId());
            ArrayNode initialHistory = calibratedHistory(characterRef);
            ObjectNode historical = characters.toState(previous, Map.of(), Map.of());
            historical.set("historyFacts", initialHistory.deepCopy());
            ObjectNode frozen = journal.emptyState();
            ((ObjectNode) frozen.path("characters")).set(characterRef, historical);
            List<WorldSettingCandidate> candidates = new ArrayList<>();
            for (int index = 0; index < CANDIDATES; index++) {
                var evidence = JSON.arrayNode().addObject().put("quote", QUOTE)
                        .put("startOffset", index * QUOTE.length()).put("endOffset", (index + 1) * QUOTE.length());
                var candidate = WorldSettingCandidate.create(work, episode, job, WorldSettingCategory.RACE,
                        "Synthetic species", "Property " + String.format("%03d", index), "Value " + index,
                        JSON.arrayNode().add(evidence), BigDecimal.ONE, null);
                em.persist(candidate);
                candidates.add(candidate);
            }
            String worldRef = "provisional-world:" + candidates.getFirst().getId();
            ObjectNode world = ((ObjectNode) frozen.path("worldSettings")).putObject(worldRef);
            world.put("category", "RACE").put("subjectName", "Synthetic species")
                    .put("provisionalSubjectKey", worldRef).put("version", 0);
            world.putObject("propertiesJson");
            world.putObject("provenanceByPath");
            for (var candidate : candidates) candidate.resolveOrderedSubject(WorldSettingSubjectResolutionType.NEW,
                    worldRef, "Synthetic species", JSON.arrayNode(), JSON.arrayNode().add(worldRef));
            addFrozenHistoryUntil(frozen, INPUT_BYTES);
            job.captureAutomaticInput(frozen);
            assertThat(states.prepareInput(job)).isTrue();
            var prepared = states.prepareState(job);
            ArrayNode history = initialHistory.deepCopy();
            RuntimeSample append;
            try (RuntimeProbe probe = new RuntimeProbe()) {
                for (int index = 0; index < HISTORY_CHANGES; index++) {
                    ObjectNode row = historyFact(INITIAL_HISTORY + index);
                    history.add(row);
                    prepared.append(List.of(change(index, characterRef, history, row)));
                }
                em.flush();
                append = probe.finish();
            }
            long inputBytes = jdbc.queryForObject("select octet_length(automatic_input_state::text) from analysis_jobs where id=?", Long.class, job.getId());
            long journalBytes = jdbc.queryForObject("select octet_length(state_journal::text) from analysis_jobs where id=?", Long.class, job.getId());
            assertThat(inputBytes).isEqualTo(INPUT_BYTES);
            if (!COMPACT) assertThat(journalBytes).isBetween((long) JOURNAL_BYTES - 4_000, (long) JOURNAL_BYTES + 4_000);
            else assertThat(journalBytes).isLessThan(100_000);
            String finalHash = journal.hash(history);
            assertThat(journal.hash(prepared.projectedState().path("characters").path(characterRef).path("historyFacts"))).isEqualTo(finalHash);
            Fixture fixture = new Fixture(job.getId(), lease, characterRef, inputBytes, journalBytes,
                    job.getInputStateHash(), journal.hash(job.getStateJournal()), finalHash);
            write("sample-" + sample + "-fixture", Map.of("fixture", fixture, "journalMode", COMPACT ? "compact" : "legacy",
                    "journalFormatVersion", job.getStateJournal().path("formatVersion").asInt(), "appendRuntime", append,
                    "heapMaxBytes", Runtime.getRuntime().maxMemory(), "historyEntries", history.size(),
                    "jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments()));
            return fixture;
        });
    }

    /** Calibrate the stored legacy array bytes, not network bytes, using only synthetic evidence. */
    private ArrayNode calibratedHistory(String ref) {
        ArrayNode initial = JSON.arrayNode();
        for (int index = 0; index < INITIAL_HISTORY; index++) initial.add(historyFact(index));
        int difference = JOURNAL_BYTES - 512 - legacyChangesBytes(ref, initial);
        assertThat(difference).isPositive();
        int each = difference / (HISTORY_CHANGES * INITIAL_HISTORY);
        for (JsonNode row : initial) ((ObjectNode) row).put("factValue", row.path("factValue").asText() + "x".repeat(each));
        int remaining = (JOURNAL_BYTES - 512 - legacyChangesBytes(ref, initial)) / HISTORY_CHANGES;
        ObjectNode last = (ObjectNode) initial.get(initial.size() - 1);
        last.put("factValue", last.path("factValue").asText() + "x".repeat(Math.max(0, remaining)));
        return initial;
    }

    private int legacyChangesBytes(String ref, ArrayNode initial) {
        ArrayNode history = initial.deepCopy();
        List<AnalysisStateChange> changes = new ArrayList<>();
        for (int index = 0; index < HISTORY_CHANGES; index++) {
            history.add(historyFact(INITIAL_HISTORY + index));
            changes.add(new AnalysisStateChange("synthetic-history:" + index, List.of("characters", ref, "historyFacts"),
                    history, false, "HISTORY_ONLY", List.of()));
        }
        return jsonbBytes(journal.toJson(changes));
    }

    private void addFrozenHistoryUntil(ObjectNode state, int targetBytes) {
        ObjectNode filler = ((ObjectNode) state.path("characters")).putObject("synthetic-unrelated-history");
        ArrayNode history = filler.putArray("historyFacts");
        int missing = targetBytes - jsonbBytes(state);
        int index = 0;
        while (missing > 2_000) {
            ObjectNode row = historyFact(10_000 + index++);
            history.add(row);
            missing -= jsonbBytes(row) + 2;
        }
        ObjectNode tail = history.addObject().put("factValue", "");
        int padding = targetBytes - jsonbBytes(state);
        assertThat(padding).isGreaterThanOrEqualTo(0);
        tail.put("factValue", "x".repeat(padding));
    }

    private int jsonbBytes(JsonNode value) {
        return jdbc.queryForObject("select octet_length(cast(? as jsonb)::text)", Integer.class, value.toString());
    }

    private static ObjectNode historyFact(int index) {
        ObjectNode row = JSON.objectNode().put("factId", UUID.nameUUIDFromBytes(("synthetic-fact-" + index).getBytes(StandardCharsets.UTF_8)).toString())
                .put("factType", "STATUS").put("factKey", "status.synthetic-" + index).put("factValue", "Ended synthetic state " + index)
                .put("operation", "HISTORY_ONLY").put("temporalScope", "PAST");
        row.putObject("valueJson").put("active", false);
        row.putObject("narrativeTime").put("label", "Synthetic past " + (index % 34 + 1));
        String quote = "synthetic-history-evidence-".repeat(16);
        row.putObject("supportingSource").put("sourceEpisodeNo", index % 34 + 1).putArray("evidenceSpans")
                .addObject().put("quote", quote).put("startOffset", 0).put("endOffset", quote.length());
        return row;
    }

    private static AnalysisStateChange change(int index, String ref, ArrayNode fullHistory, ObjectNode newRow) {
        if (!COMPACT) return new AnalysisStateChange("synthetic-history:" + index,
                List.of("characters", ref, "historyFacts"), fullHistory, false, "HISTORY_ONLY", List.of());
        // Reflection keeps this exact test source compilable on the unchanged baseline checkout.
        try {
            return (AnalysisStateChange) AnalysisStateChange.class.getMethod("appendHistory", String.class, String.class,
                    JsonNode.class, String.class, List.class).invoke(null, "synthetic-history:" + index, ref,
                    JSON.arrayNode().add(newRow), "HISTORY_ONLY", List.of());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Compact mode needs the GH365 append API", failure); }
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    private static void write(String phase, Object value) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            String directory = System.getenv("GH365_PERFORMANCE_OUTPUT");
            if (directory == null || directory.isBlank()) throw new IllegalArgumentException("GH365_PERFORMANCE_OUTPUT is required");
            Path output = Path.of(directory);
            Files.createDirectories(output);
            Files.writeString(output.resolve(phase + ".json"), json + "\n", StandardCharsets.UTF_8);
            System.out.println("GH365_PERFORMANCE " + phase);
        } catch (Exception failure) { throw new IllegalStateException("Cannot record synthetic measurement", failure); }
    }

    private record Fixture(UUID job, UUID lease, String characterRef, long inputBytes, long journalBytes,
            String inputHash, String journalHash, String finalHistoryHash) {}
    private record RuntimeSample(double elapsedMillis, long heapBeforeBytes, long heapAfterBytes,
            long sampledPeakHeapBytes, long allocatedBytes, long gcCollections, long gcMillis, long processCpuNanos) {}

    /** Sampled JVM heap is not RSS or a guaranteed absolute peak. Allocation is process-wide platform-thread allocation. */
    private static final class RuntimeProbe implements AutoCloseable {
        private final long started = System.nanoTime();
        private final long heapBefore = usedHeap();
        private final long allocationBefore = allocations();
        private final long collectionsBefore = collections();
        private final long gcBefore = gcTime();
        private final long cpuBefore = cpu();
        private final AtomicLong peak = new AtomicLong(heapBefore);
        private final Thread sampler;
        private volatile boolean sampling = true;
        private RuntimeSample result;
        RuntimeProbe() {
            sampler = Thread.ofPlatform().daemon().name("gh365-heap-sampler").start(() -> {
                while (sampling) {
                    peak.accumulateAndGet(usedHeap(), Math::max);
                    try { Thread.sleep(5); } catch (InterruptedException ignored) { break; }
                }
            });
        }
        RuntimeSample finish() {
            if (result != null) return result;
            sampling = false;
            sampler.interrupt();
            long after = usedHeap();
            peak.accumulateAndGet(after, Math::max);
            result = new RuntimeSample((System.nanoTime() - started) / 1_000_000.0, heapBefore, after, peak.get(),
                    allocations() - allocationBefore, collections() - collectionsBefore, gcTime() - gcBefore, cpu() - cpuBefore);
            return result;
        }
        @Override public void close() { finish(); }
        private static long usedHeap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
        private static long allocations() {
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemorySupported()) return -1;
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            return bean.getTotalThreadAllocatedBytes();
        }
        private static long collections() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum(); }
        private static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum(); }
        private static long cpu() { return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(); }
    }
}
