package org.monitoring.catchholebackend.domain.analysis.service;

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
import java.util.LinkedHashMap;
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
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.*;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.worldsetting.type.*;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Synthetic scale reproduction of initial episode claim, not the GH365 comparison-batch claim. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "internal.api-key=synthetic-gh374-key", "relationship.graph.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "GH374_MEMORY_PROFILE", matches = "true")
@Import(AnalysisHistoryMemoryPostgresIntegrationTest.MetricsConfiguration.class)
@DisplayName("41개 과거 Job·1768개 후보가 있는 다음 자동 회차의 실제 HTTP 메모리 회귀")
class AnalysisHistoryMemoryPostgresIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAST_JOBS = 41;
    private static final long INPUT_TOTAL = 34_344_206;
    private static final long BASE_TOTAL = 11_282_343;
    private static final long JOURNAL_V1_TOTAL = 29_538_310;
    private static final long JOURNAL_V2_TOTAL = 810_061;
    private static final int CURRENT_INPUT_BYTES = 1_776_908;
    private static final String QUOTE = "Synthetic retained evidence. ";
    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final boolean OVERLAP = "overlap".equals(System.getenv("GH374_SCENARIO"));
    private static final boolean BASELINE = Boolean.parseBoolean(System.getenv("GH374_MEASURE_BASELINE"));

    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh374_memory_test").withEnv("POSTGRES_USER", "gh374_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-gh374-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/gh374_memory_test");
        p.add("spring.datasource.username", () -> "gh374_test");
        p.add("spring.datasource.password", () -> "disposable-gh374-test-only");
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
    @Autowired AnalysisStateJournal journal;
    @Autowired List<AnalysisStateSource> sources;
    @Autowired AnalysisPendingReferenceSource pending;
    @Autowired JwtTokenProvider tokens;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.monitoring.catchholebackend.global.storage.ObjectStorage objectStorage;

    @Test @DisplayName("768MiB 동일 조건에서 회차 목록과 다음 회차 claim의 모든 결과·근거를 보존하고 실제 JDBC·힙을 측정한다")
    void initialClaimAndEpisodeListKeepAllEvidenceAtObservedHistoryScale() throws Exception {
        int samples = Integer.parseInt(System.getenv().getOrDefault("GH374_SAMPLES", "3"));
        assertThat(samples).isBetween(1, 5);
        for (int sample = 1; sample <= samples; sample++) {
            Fixture fixture = fixture(sample);
            String retained = retainedDigest(fixture.work());
            System.gc();
            try (HttpClient client = HttpClient.newHttpClient()) {
                Payloads payloads = requests(client, sample, fixture);
                JsonNode episodePayload = payloads.episodes();
                JsonNode claimed = payloads.claim();
                assertThat(episodePayload).hasSize(42);
                assertThat(episodePayload.get(0).path("episodeNo").asInt()).isEqualTo(42);
                assertThat(episodePayload.get(0).path("analysisStatus").asText()).isEqualTo("IN_PROGRESS");
                assertThat(claimed.path("analysisJobId").asText()).isEqualTo(fixture.job().toString());
                assertThat(claimed.path("knownCharacters")).hasSize(41);
                assertThat(claimed.path("semanticCurrentContext").path("characters")).hasSize(41);
                JsonNode input = MAPPER.readTree(jdbc.queryForObject("select automatic_input_state::text from analysis_jobs where id=?", String.class, fixture.job()));
                assertThat(input.path("characters")).hasSize(41);
                assertThat(input.path("worldSettings")).hasSize(181);
                assertThat(input.path("references")).hasSize(3);
                List<String> referenceIds = new ArrayList<>(); input.path("references").fieldNames().forEachRemaining(referenceIds::add);
                referenceIds.sort(String::compareTo);
                List<String> referenceNames = referenceIds.stream().map(id -> input.path("references").path(id).path("settingName").asText()).toList();
                assertThat(claimed.path("analysisContext").path("unresolvedReferences"))
                        .extracting(row -> row.path("settingName").asText()).containsExactlyElementsOf(referenceNames);
                long facts = 0, verified = 0;
                for (var owner : input.path("characters")) {
                    for (var history : owner.path("historyFacts")) {
                        facts++;
                        if (history.path("supportingSource").path("availability").asText().equals("VERIFIED")) {
                            verified++;
                            assertThat(history.path("supportingSource").path("evidenceSpans").get(0).path("quote").asText()).isEqualTo(QUOTE);
                        }
                    }
                    for (var slot : owner.path("slots")) facts += slot.path("sourceFacts").size();
                }
                // The fixture stores all 641 facts as retained history, not as additional inferred current facts.
                assertThat(facts).isEqualTo(641);
                assertThat(verified).isEqualTo(641);
                String inputHash = journal.hash(input);
                assertThat(claimed.path("analysisContext").path("inputStateHash").asText()).isEqualTo(inputHash);
                assertThat(inputHash).isEqualTo(fixture.expectedInputHash());
                assertThat(retainedDigest(fixture.work())).isEqualTo(retained);
                ObjectNode normalizedClaim = claimed.deepCopy();
                normalizedClaim.remove(List.of("leaseToken", "leaseExpiresAt"));
                ((ObjectNode) normalizedClaim.path("analysisContext")).remove("inputStateHash");
                ((ObjectNode) normalizedClaim.path("semanticCurrentContext")).remove("inputStateHash");
                JsonNode semanticEpisodes = episodePayload.deepCopy();
                for (var episode : semanticEpisodes) ((ObjectNode) episode).remove(List.of("createdAt", "updatedAt", "contentUpdatedAt"));
                if (OVERLAP) {
                    assertThat(semanticEpisodes.get(0).path("status").asText()).isIn("UPLOADED", "CHUNKING");
                    ((ObjectNode) semanticEpisodes.get(0)).put("status", "CONCURRENT_INITIAL_CLAIM");
                }
                JsonNode semanticInput = semanticValue(input, fixture.labels());
                JsonNode semanticClaim = semanticValue(normalizedClaim, fixture.labels());
                JsonNode semanticList = semanticValue(semanticEpisodes, fixture.labels());
                // Local-only synthetic values make any cross-revision hash mismatch inspectable.
                write("sample-" + sample + "-semantic-input-value", semanticInput);
                write("sample-" + sample + "-semantic-claim-value", semanticClaim);
                write("sample-" + sample + "-semantic-episodes-value", semanticList);
                write("sample-" + sample + "-parity", Map.of(
                        "inputSemanticHash", journal.hash(semanticInput),
                        "claimSemanticHash", journal.hash(semanticClaim),
                        "episodeListSemanticHash", journal.hash(semanticList),
                        "verifiedHistoryFacts", verified, "pendingReferences", input.path("references").size(),
                        "currentWorldSubjects", input.path("worldSettings").size(), "retainedRowsUnchanged", true));
                // Make later samples independently claimable without rerunning analysis or consuming provider tokens.
                jdbc.update("update analysis_jobs set status='SUCCEEDED', lease_token=null, lease_expires_at=null where id=?", fixture.job());
            }
        }
        org.mockito.Mockito.verifyNoInteractions(objectStorage);
    }

    private Payloads requests(HttpClient client, int sample, Fixture fixture) throws Exception {
        HttpRequest episodes = HttpRequest.newBuilder(url("/api/v1/works/" + fixture.work() + "/episodes"))
                .timeout(Duration.ofSeconds(180)).header("Authorization", "Bearer " + fixture.token()).GET().build();
        ObjectNode body = JSON.objectNode().put("modelName", "synthetic-gh374").put("currentStep", "SYNTHETIC_INITIAL_CLAIM")
                .put("supportsFrozenComparisonEvidence", true).put("supportsSemanticCurrentContext", true)
                .put("supportsRelationshipPartialResults", true);
        body.putArray("allowedJobTypes").add("SETTING_EXTRACTION");
        body.putArray("supportedAnalysisModes").add("ORDERED_PROVISIONAL");
        HttpRequest claim = HttpRequest.newBuilder(url("/api/internal/v1/analysis-jobs/claim?includeReferenceSelection=true"))
                .timeout(Duration.ofSeconds(180)).header("X-Internal-Api-Key", "synthetic-gh374-key")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        if (!OVERLAP) {
            JsonNode listed = request(client, sample, "episodes", fixture, episodes);
            System.gc();
            return new Payloads(listed, request(client, sample, "claim", fixture, claim));
        }
        String name = "sample-" + sample + "-overlap";
        write(name + "-started", Map.of("phase", "overlap", "clientRequests", 2,
                "historicalJobJsonTextBytes", INPUT_TOTAL + BASE_TOTAL + JOURNAL_V1_TOTAL + JOURNAL_V2_TOTAL));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        HttpResponse<String> listed, claimed;
        RuntimeSample runtime;
        List<JdbcResultMetrics.QueryResult> queries;
        long[] spans = new long[4];
        JdbcResultMetrics.start();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2); RuntimeProbe probe = new RuntimeProbe()) {
            var listFuture = executor.submit(() -> { ready.countDown(); start.await(); spans[0] = System.nanoTime();
                try { return client.send(episodes, HttpResponse.BodyHandlers.ofString()); } finally { spans[1] = System.nanoTime(); } });
            var claimFuture = executor.submit(() -> { ready.countDown(); start.await(); spans[2] = System.nanoTime();
                try { return client.send(claim, HttpResponse.BodyHandlers.ofString()); } finally { spans[3] = System.nanoTime(); } });
            ready.await(); start.countDown();
            try { listed = listFuture.get(240, java.util.concurrent.TimeUnit.SECONDS); claimed = claimFuture.get(240, java.util.concurrent.TimeUnit.SECONDS); }
            finally { runtime = probe.finish(); queries = JdbcResultMetrics.stop(); }
        }
        double overlap = (Math.min(spans[1], spans[3]) - Math.max(spans[0], spans[2])) / 1_000_000.0;
        long rows = queries.stream().filter(JdbcResultMetrics.QueryResult::selectsJobJson).mapToLong(JdbcResultMetrics.QueryResult::consumedRows).sum();
        write(name, Map.of("runtime", runtime, "queries", queries, "httpStatus", Math.max(listed.statusCode(), claimed.statusCode()),
                "responseStatuses", List.of(listed.statusCode(), claimed.statusCode()),
                "responseBytes", listed.body().getBytes(StandardCharsets.UTF_8).length + claimed.body().getBytes(StandardCharsets.UTF_8).length,
                "rowsSelectingJobJson", rows, "clientOverlapMillis", overlap));
        assertThat(overlap).as("both bounded client request intervals actually overlap").isPositive();
        assertThat(listed.statusCode()).isEqualTo(200); assertThat(claimed.statusCode()).isEqualTo(200);
        if (!BASELINE) assertThat(rows).as("only the claimed current Job may select its JSON; historical Jobs must remain metadata-only").isEqualTo(1);
        return new Payloads(MAPPER.readTree(listed.body()).path("data"), MAPPER.readTree(claimed.body()).path("data"));
    }
    private record Payloads(JsonNode episodes, JsonNode claim) {}

    private JsonNode request(HttpClient client, int sample, String phase, Fixture fixture, HttpRequest request) throws Exception {
        String name = "sample-" + sample + "-" + phase;
        write(name + "-started", Map.of("phase", phase, "historyJobCount", PAST_JOBS, "historicalJobJsonTextBytes", INPUT_TOTAL + BASE_TOTAL + JOURNAL_V1_TOTAL + JOURNAL_V2_TOTAL));
        HttpResponse<String> response;
        RuntimeSample runtime;
        List<JdbcResultMetrics.QueryResult> queries;
        JdbcResultMetrics.start();
        try (RuntimeProbe probe = new RuntimeProbe()) {
            try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
            finally { runtime = probe.finish(); queries = JdbcResultMetrics.stop(); }
        }
        long rows = queries.stream().filter(JdbcResultMetrics.QueryResult::selectsJobJson).mapToLong(JdbcResultMetrics.QueryResult::consumedRows).sum();
        write(name, Map.of("runtime", runtime, "queries", queries, "httpStatus", response.statusCode(),
                "responseBytes", response.body().getBytes(StandardCharsets.UTF_8).length, "rowsSelectingJobJson", rows));
        assertThat(response.statusCode()).as("synthetic %s HTTP status; no response body is logged", phase).isEqualTo(200);
        if (!BASELINE && phase.equals("episodes")) assertThat(rows).as("episode list must not hydrate a historical Job JSON").isZero();
        if (!BASELINE && phase.equals("claim")) assertThat(rows).as("the initial current Job is the only full Job read").isEqualTo(1);
        return MAPPER.readTree(response.body()).path("data");
    }

    private Fixture fixture(int sample) throws Exception {
        var labels = new LinkedHashMap<String, String>();
        Seed seed = tx().execute(status -> {
            Member member = Member.register("gh374-" + UUID.randomUUID() + "@example.invalid", "test-only", null, "Synthetic author");
            em.persist(member);
            Work work = Work.create(member, "GH374 synthetic work", WorkGenre.FANTASY, "No production material");
            em.persist(work); label(labels, work.getId(), "work");
            UploadBatch oldBatch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_SINGLE_FILE, UploadSourceType.FILE);
            UploadBatch currentBatch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_SINGLE_FILE, UploadSourceType.FILE);
            em.persist(oldBatch); em.persist(currentBatch);
            label(labels, oldBatch.getId(), "old-batch"); label(labels, currentBatch.getId(), "current-batch");
            UploadFile oldFile = UploadFile.create(oldBatch, UploadFileRole.EPISODE, "synthetic-old.txt", "text/plain", "gh374/old-upload", 100);
            UploadFile currentFile = UploadFile.create(currentBatch, UploadFileRole.EPISODE, "synthetic-current.txt", "text/plain", "gh374/current-upload", 100);
            em.persist(oldFile); em.persist(currentFile);
            label(labels, oldFile.getId(), "old-file"); label(labels, currentFile.getId(), "current-file");
            List<Episode> episodes = new ArrayList<>();
            List<AnalysisJob> jobs = new ArrayList<>();
            List<WorkCharacter> characters = new ArrayList<>();
            List<WorldSetting> worlds = new ArrayList<>();
            String sourceHash = new org.monitoring.catchholebackend.global.hash.Sha256Hasher().hash(QUOTE);
            for (int i = 1; i <= 42; i++) {
                Episode episode = Episode.create(work, i < 41 ? oldFile.getId() : currentFile.getId(), i, "Synthetic episode " + i, "gh374/episode-" + i,
                        "synthetic-v1", sourceHash, QUOTE.length());
                em.persist(episode); label(labels, episode.getId(), "episode-" + i); episodes.add(episode);
                AnalysisJob job = AnalysisJob.create(work, i < 41 ? oldBatch : currentBatch, episode, AnalysisJobType.SETTING_EXTRACTION);
                job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
                UUID run = i == 42 ? jobs.get(40).getAnalysisRunId() : UUID.randomUUID();
                label(labels, run, i >= 41 ? "current-run" : "run-" + i);
                job.initializeOrderedRun(run, 1, i == 42 ? 1 : 0, i == 42 ? jobs.get(40).getId() : null, i == 42 ? null : journal.emptyState());
                em.persist(job); label(labels, job.getId(), "job-" + i); jobs.add(job);
                if (i <= 41) {
                    ObjectNode initial = journal.emptyState();
                    String emptyHash = journal.hash(initial);
                    job.captureAutomaticInput(initial);
                    job.prepareOrderedInput(emptyHash);
                    ObjectNode envelope = JSON.objectNode().put("formatVersion", 1).put("runId", run.toString())
                            .put("workId", work.getId().toString()).put("generation", 1).put("sequence", 0)
                            .put("episodeNo", i).put("jobId", job.getId().toString())
                            .put("inputStateHash", emptyHash).put("outputStateHash", emptyHash);
                    envelope.putArray("changes"); job.replacePendingJournal(envelope); job.sealJournal();
                    job.succeed("{\"unresolvedFindingCount\":0}", 10, 5);
                    ReflectionTestUtils.setField(job, "automaticAppliedAt", CLOCK.plusSeconds(i));
                    episode.markAnalyzed();
                }
            }
            em.flush();
            for (int i = 0; i < 41; i++) {
                jdbc.update("insert into episode_chunks(id,episode_id,chunk_index,chunk_text,start_offset,end_offset,paragraph_start_index,paragraph_end_index,created_at,updated_at) values(?,?,0,?,0,?,0,0,?,?)",
                        UUID.randomUUID(), episodes.get(i).getId(), QUOTE, QUOTE.length(), CLOCK, CLOCK);
                var character = WorkCharacter.create(work, "Synthetic character " + i, null, null, null,
                        JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), JSON.objectNode(), episodes.get(i).getId());
                em.persist(character); label(labels, character.getId(), "character-" + i); characters.add(character);
            }
            for (int i = 0; i < 181; i++) {
                var properties = new LinkedHashMap<String,String>();
                for (int n = i; n < 871; n += 181) properties.put("Property " + n, "Synthetic value " + n);
                var world = WorldSetting.create(work, WorldSettingCategory.RACE, "Synthetic subject " + i, properties);
                em.persist(world); label(labels, world.getId(), "world-" + i); worlds.add(world);
            }
            List<UUID> factIds = new ArrayList<>();
            List<UUID> characterCandidateIds = new ArrayList<>();
            List<UUID> worldCandidateIds = new ArrayList<>();
            for (int i = 0; i < 832; i++) {
                var job = jobs.get(i % 41); var episode = episodes.get(i % 41); var character = characters.get(i % 41);
                var candidate = SettingCandidate.create(work, episode, null, job, SettingEntityType.CHARACTER,
                        character.getName(), character.getName(), character.getId(), SettingCandidateMatchStatus.MATCHED,
                        "profile.synthetic_" + i, "Synthetic fact " + i, SettingValueType.STRING,
                        JSON.objectNode().put("value", "Synthetic fact " + i), evidence(), BigDecimal.ONE,
                        JSON.objectNode().put("synthetic", "retained raw result " + i));
                em.persist(candidate); label(labels, candidate.getId(), "character-candidate-" + i); characterCandidateIds.add(candidate.getId());
                if (i < 641) {
                    candidate.confirm();
                    var fact = CharacterFact.create(character, candidate, CharacterFactType.PROFILE, "profile.synthetic_" + i,
                            "Synthetic fact " + i, "Synthetic fact " + i, candidate.getValueJson(), episode, null, job, BigDecimal.ONE, i % 41 + 1);
                    em.persist(fact); label(labels, fact.getId(), "fact-" + i); factIds.add(fact.getId());
                } else candidate.dismiss();
            }
            for (int i = 0; i < 936; i++) {
                var candidate = WorldSettingCandidate.create(work, episodes.get(i % 41), jobs.get(i % 41), WorldSettingCategory.RACE,
                        "Synthetic subject " + (i % 181), "Property " + i, "Synthetic value " + i,
                        evidence(), BigDecimal.ONE, null);
                em.persist(candidate); label(labels, candidate.getId(), "world-candidate-" + i); worldCandidateIds.add(candidate.getId());
                if (i < 871) {
                    ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
                    candidate.confirm(WorldSettingOperation.ADD, WorldSettingCategory.RACE, "Synthetic subject " + (i % 181),
                            "Property " + i, "Synthetic value " + i, null, member, worlds.get(i % 181));
                } else if (i < 933) candidate.dismiss(null, member);
                else ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.FAILED);
            }
            em.flush();
            for (int i = 0; i < characterCandidateIds.size(); i++) jdbc.update("update setting_candidates set created_at=?,updated_at=? where id=?", CLOCK.plusSeconds(i), CLOCK.plusSeconds(i), characterCandidateIds.get(i));
            for (int i = 0; i < worldCandidateIds.size(); i++) jdbc.update("update world_setting_candidates set created_at=?,updated_at=?,reviewed_at=case when reviewed_at is null then null else cast(? as timestamp) end where id=?", CLOCK.plusSeconds(i), CLOCK.plusSeconds(i), CLOCK.plusSeconds(i), worldCandidateIds.get(i));
            for (int i = 0; i < characters.size(); i++) jdbc.update("update characters set created_at=?,updated_at=? where id=?", CLOCK.plusSeconds(i), CLOCK.plusSeconds(i), characters.get(i).getId());
            for (int i = 0; i < factIds.size(); i++) jdbc.update("update character_facts set created_at=?,updated_at=? where id=?", CLOCK.plusSeconds(i), CLOCK.plusSeconds(i), factIds.get(i));
            for (int i = 0; i < 42; i++) jdbc.update("update analysis_jobs set created_at=?,updated_at=? where id=?", CLOCK.plusSeconds(i), CLOCK.plusSeconds(i), jobs.get(i).getId());
            return new Seed(work.getId(), jobs.getLast().getId(), jobs.stream().limit(41).map(AnalysisJob::getId).toList(), factIds, tokens.generateAccessToken(member));
        });
        // Calibrate unused raw payload totals separately; they remain stored and are covered by the row digest.
        calibrateRaw(seed.work(), "setting_candidates", "raw_ai_result_json", "CONFIRMED", 4_708_279);
        calibrateRaw(seed.work(), "setting_candidates", "raw_ai_result_json", "DISMISSED", 189_064);
        calibrateRaw(seed.work(), "world_setting_candidates", "raw_comparison_json", "CONFIRMED", 564_400);
        calibrateRaw(seed.work(), "world_setting_candidates", "raw_comparison_json", "DISMISSED", 37_439);
        calibrateRaw(seed.work(), "world_setting_candidates", "raw_comparison_json", "PENDING_REVIEW", 1_852);
        // Calibrate genuine retained fact text, keeping the number of facts/evidence unchanged.
        ObjectNode before = capture(seed);
        int missing = CURRENT_INPUT_BYTES - jsonbBytes(before);
        assertThat(missing).as("synthetic state is smaller than observed target before padding").isPositive();
        for (int i = 0; i < seed.facts().size(); i++) jdbc.update("update character_facts set fact_value=fact_value || ? where id=?",
                "x".repeat(missing / seed.facts().size() + (i < missing % seed.facts().size() ? 1 : 0)), seed.facts().get(i));
        ObjectNode expected = capture(seed);
        assertThat(jsonbBytes(expected)).isEqualTo(CURRENT_INPUT_BYTES);
        String inputHash = journal.hash(expected);
        String expectedSemanticHash = semanticHash(expected, labels);
        before = null; expected = null;
        for (int index = 0; index < 41; index++) installHistoricalJson(seed.pastJobs().get(index), index);
        List<String> replayHashes = new ArrayList<>();
        for (int index : List.of(0, 40)) replayHashes.add(tx().execute(status -> {
            AnalysisJob old = em.find(AnalysisJob.class, seed.pastJobs().get(index));
            JsonNode replayed = states.getProjectedState(old);
            assertThat(journal.hash(replayed)).isEqualTo(old.getStateJournal().path("outputStateHash").asText());
            return semanticHash(replayed, labels);
        }));
        List<Map<String,Object>> totals = jdbc.queryForList("""
                select count(*) as jobs,sum(octet_length(automatic_input_state::text)) as input_bytes,
                    sum(octet_length(run_base_state::text)) as base_bytes,sum(octet_length(state_journal::text)) as journal_bytes,
                    count(*) filter (where state_journal->>'formatVersion'='1') as v1_jobs,
                    count(*) filter (where state_journal->>'formatVersion'='2') as v2_jobs,
                    sum(octet_length(state_journal::text)) filter (where state_journal->>'formatVersion'='1') as v1_bytes,
                    sum(octet_length(state_journal::text)) filter (where state_journal->>'formatVersion'='2') as v2_bytes
                from analysis_jobs where work_id=? and source_episode_no<42
                """, seed.work());
        assertThat(((Number) totals.getFirst().get("input_bytes")).longValue()).isEqualTo(INPUT_TOTAL);
        assertThat(((Number) totals.getFirst().get("base_bytes")).longValue()).isEqualTo(BASE_TOTAL);
        assertThat(((Number) totals.getFirst().get("journal_bytes")).longValue()).isEqualTo(JOURNAL_V1_TOTAL + JOURNAL_V2_TOTAL);
        assertThat(((Number) totals.getFirst().get("v1_jobs")).longValue()).isEqualTo(35);
        assertThat(((Number) totals.getFirst().get("v2_jobs")).longValue()).isEqualTo(6);
        assertThat(((Number) totals.getFirst().get("v1_bytes")).longValue()).isEqualTo(JOURNAL_V1_TOTAL);
        assertThat(((Number) totals.getFirst().get("v2_bytes")).longValue()).isEqualTo(JOURNAL_V2_TOTAL);
        write("sample-" + sample + "-environment", Map.of(
                "operatingSystem", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                "architecture", System.getProperty("os.arch"), "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "databaseVersion", jdbc.queryForObject("select version()", String.class)));
        write("sample-" + sample + "-fixture", Map.of("historicalJsonTotals", totals, "characterCandidates", 832,
                "worldCandidates", 936, "characterFacts", 641, "currentInputJsonbTextBytes", CURRENT_INPUT_BYTES,
                "expectedInputSemanticHash", expectedSemanticHash, "historicalV1V2ReplayHashes", replayHashes, "heapMaxBytes", Runtime.getRuntime().maxMemory(),
                "jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        return new Fixture(seed.work(), seed.job(), seed.token(), labels, inputHash);
    }

    private void calibrateRaw(UUID work, String table, String column, String status, long target) {
        // All identifiers are hard-coded test constants, never application input.
        List<UUID> ids = jdbc.queryForList("select id from " + table + " where work_id=? and review_status=? order by created_at,id", UUID.class, work, status);
        int emptyBytes = jsonbBytes(JSON.objectNode().put("synthetic", ""));
        for (int i = 0; i < ids.size(); i++) {
            int length = (int) split(target, i, ids.size()) - emptyBytes;
            jdbc.update("update " + table + " set " + column + "=jsonb_build_object('synthetic',cast(? as text)) where id=?", "x".repeat(length), ids.get(i));
        }
        assertThat(jdbc.queryForObject("select sum(octet_length(" + column + "::text)) from " + table + " where work_id=? and review_status=?", Long.class, work, status)).isEqualTo(target);
    }

    private ObjectNode capture(Seed seed) {
        return tx().execute(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, seed.job());
            ObjectNode input = journal.emptyState();
            for (AnalysisStateSource source : sources) input.set(source.domain(), source.capture(job.getWork()));
            ((ObjectNode) input.path("references")).setAll(pending.capture(job));
            return input;
        });
    }

    private void installHistoricalJson(UUID id, int index) {
        tx().executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            int inputTarget = (int) split(INPUT_TOTAL, index, 41);
            int baseTarget = (int) split(BASE_TOTAL, index, 41);
            int journalTarget = (int) (index < 35 ? split(JOURNAL_V1_TOTAL, index, 35) : split(JOURNAL_V2_TOTAL, index - 35, 6));
            ObjectNode input = paddedState(inputTarget, index);
            ObjectNode base = paddedState(baseTarget, index);
            String inputHash = journal.hash(input);
            var changes = new ArrayList<AnalysisStateChange>();
            ArrayNode accumulated = JSON.arrayNode();
            for (int i = 0; i < 20; i++) {
                ObjectNode row = history(i);
                accumulated.add(row);
                changes.add(index < 35 ? new AnalysisStateChange("synthetic-history-" + i,
                        List.of("characters", "synthetic-journal-owner", "historyFacts"), accumulated, false, "HISTORY_ONLY", List.of())
                        : AnalysisStateChange.appendHistory("synthetic-history-" + i, "synthetic-journal-owner", JSON.arrayNode().add(row), "HISTORY_ONLY", List.of()));
            }
            ObjectNode envelope = JSON.objectNode().put("formatVersion", index < 35 ? 1 : 2)
                    .put("runId", job.getAnalysisRunId().toString()).put("workId", job.getWork().getId().toString())
                    .put("generation", 1).put("sequence", 0).put("episodeNo", index + 1).put("jobId", id.toString())
                    .put("inputStateHash", inputHash).put("outputStateHash", "0".repeat(64));
            ArrayNode stored = journal.toJson(changes); envelope.set("changes", stored);
            // Spread padding over genuine history values, avoiding one giant artificial blob.
            List<ObjectNode> rows = new ArrayList<>();
            for (var change : stored) for (var row : change.path("value")) rows.add((ObjectNode) row);
            padRows(envelope, rows, journalTarget);
            JsonNode projected = journal.apply(input, journal.fromJson(stored));
            envelope.put("outputStateHash", journal.hash(projected));
            assertThat(jsonbBytes(envelope)).isEqualTo(journalTarget);
            jdbc.update("update analysis_jobs set automatic_input_state=cast(? as jsonb),run_base_state=cast(? as jsonb),state_journal=cast(? as jsonb),input_state_hash=? where id=?",
                    input.toString(), base.toString(), envelope.toString(), inputHash, id);
            // v1/v2 are both replayed and retain their final history/evidence before measuring HTTP.
            assertThat(journal.hash(journal.apply(input, journal.fromJson(envelope.path("changes"))))).isEqualTo(envelope.path("outputStateHash").asText());
        });
    }

    private ObjectNode paddedState(int bytes, int index) {
        ObjectNode state = journal.emptyState();
        ObjectNode owner = ((ObjectNode) state.path("characters")).putObject("synthetic-historical-owner");
        ArrayNode history = owner.putArray("historyFacts");
        List<ObjectNode> rows = new ArrayList<>();
        for (int i = 0; i < Math.max(1, bytes / 1000); i++) { var row = history(index * 10000 + i); history.add(row); rows.add(row); }
        ((ObjectNode) state.path("characters")).putObject("synthetic-journal-owner").putArray("historyFacts");
        padRows(state, rows, bytes);
        return state;
    }
    private void padRows(JsonNode root, List<ObjectNode> rows, int target) {
        int extra = target - jsonbBytes(root);
        assertThat(extra).isGreaterThanOrEqualTo(0);
        for (int i = 0; i < rows.size(); i++) {
            ObjectNode row = rows.get(i);
            row.put("factValue", row.path("factValue").asText() + "x".repeat(extra / rows.size() + (i < extra % rows.size() ? 1 : 0)));
        }
        assertThat(jsonbBytes(root)).isEqualTo(target);
    }
    private static ObjectNode history(int index) {
        ObjectNode row = JSON.objectNode().put("factType", "PROFILE").put("factKey", "profile.synthetic-" + index)
                .put("factValue", "Retained synthetic historical fact " + index).put("operation", "HISTORY_ONLY");
        row.set("evidenceSpans", evidence());
        return row;
    }
    private static ArrayNode evidence() { return JSON.arrayNode().add(JSON.objectNode().put("quote", QUOTE).put("startOffset", 0).put("endOffset", QUOTE.length())); }
    private static long split(long total, int index, int size) { return total / size + (index < total % size ? 1 : 0); }
    private int jsonbBytes(JsonNode value) { return jdbc.queryForObject("select octet_length(cast(? as jsonb)::text)", Integer.class, value.toString()); }
    private URI url(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private static void label(Map<String,String> labels, UUID id, String name) { labels.putIfAbsent(id.toString(), "synthetic-" + name); }
    private String semanticHash(JsonNode node, Map<String,String> labels) { return journal.hash(semanticValue(node, labels)); }
    private JsonNode semanticValue(JsonNode node, Map<String,String> labels) {
        JsonNode normalized = normalizeIdentities(node, labels);
        // Owner arrays are ordered by random UUID in the production mapper. Fact/evidence order stays intact.
        JsonNode semantic = normalized.path("semanticCurrentContext");
        for (String field : List.of("characters", "worldSubjects")) {
            if (!semantic.path(field).isArray()) continue;
            var rows = new ArrayList<JsonNode>(); semantic.path(field).forEach(rows::add);
            String key = field.equals("characters") ? "characterId" : "subjectId";
            rows.sort(java.util.Comparator.comparing(row -> row.path(key).asText()));
            ArrayNode ordered = JSON.arrayNode(); rows.forEach(ordered::add);
            ((ObjectNode) semantic).set(field, ordered);
        }
        JsonNode context = normalized.path("analysisContext");
        if (context.path("unresolvedReferences").isArray()) {
            var references = new ArrayList<JsonNode>(); context.path("unresolvedReferences").forEach(references::add);
            references.sort(java.util.Comparator.comparing(row -> row.path("domain").asText() + ":" + row.path("settingName").asText()));
            ArrayNode ordered = JSON.arrayNode(); references.forEach(ordered::add);
            ((ObjectNode) context).set("unresolvedReferences", ordered);
        }
        return normalized;
    }
    private JsonNode normalizeIdentities(JsonNode node, Map<String,String> labels) {
        if (node.isObject()) {
            ObjectNode normalized = JSON.objectNode();
            node.fields().forEachRemaining(field -> normalized.set(normalizeText(field.getKey(), labels), normalizeIdentities(field.getValue(), labels)));
            return normalized;
        }
        if (node.isArray()) {
            ArrayNode normalized = JSON.arrayNode(); node.forEach(child -> normalized.add(normalizeIdentities(child, labels))); return normalized;
        }
        return node.isTextual() ? JSON.textNode(normalizeText(node.asText(), labels)) : node.deepCopy();
    }
    private static final java.util.regex.Pattern UUID_TEXT = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private String normalizeText(String text, Map<String,String> labels) {
        return UUID_TEXT.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(labels.getOrDefault(match.group(), match.group())));
    }
    private String retainedDigest(UUID work) {
        return jdbc.queryForObject("""
                select md5(string_agg(t.kind || t.id || t.digest, '' order by t.kind,t.id)) from (
                  select 'job' as kind,id::text,md5(coalesce(automatic_input_state::text,'')||coalesce(run_base_state::text,'')||coalesce(state_journal::text,'')) as digest
                  from analysis_jobs where work_id=? and source_episode_no<42
                  union all select 'character',id::text,md5(row_to_json(c)::text) from setting_candidates c where work_id=?
                  union all select 'world',id::text,md5(row_to_json(c)::text) from world_setting_candidates c where work_id=?
                  union all select 'fact',f.id::text,md5(row_to_json(f)::text) from character_facts f join characters c on c.id=f.character_id where c.work_id=?
                ) t
                """, String.class, work, work, work, work);
    }
    private static void write(String phase, Object value) {
        try {
            Path output = Path.of(System.getenv("GH374_PERFORMANCE_OUTPUT")); Files.createDirectories(output);
            Files.writeString(output.resolve(phase + ".json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
            System.out.println("GH374_PERFORMANCE " + phase);
        } catch (Exception failure) { throw new IllegalStateException("Cannot record synthetic measurement", failure); }
    }
    private record Seed(UUID work, UUID job, List<UUID> pastJobs, List<UUID> facts, String token) {}
    private record Fixture(UUID work, UUID job, String token, Map<String,String> labels, String expectedInputHash) {}
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
            sampler = Thread.ofPlatform().daemon().name("gh374-heap-sampler").start(() -> {
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
