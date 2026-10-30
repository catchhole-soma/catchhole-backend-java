package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisFailureNotificationMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisFailureNotificationDispatcher;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisFailureNotificationRepository;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.*;
import org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonJobCoordinator;
import org.monitoring.catchholebackend.domain.character.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Synthetic sources only. Spring HTTP, PostgreSQL triggers and the optional Python process are real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "internal.api-key=synthetic-gh304-key")
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.context.annotation.Import(ComparisonEvidenceHttpPostgresIntegrationTest.LegacyReceiver.class)
@DisplayName("회차 비교 근거와 실패 알림의 실제 HTTP·PostgreSQL 연결")
class ComparisonEvidenceHttpPostgresIntegrationTest {
    @Container static final GenericContainer<?> PG = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "evidence_test").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "evidence_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://"+PG.getHost()+":"+PG.getMappedPort(5432)+"/evidence_test");
        p.add("spring.datasource.username", () -> "evidence_test"); p.add("spring.datasource.password", () -> "test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate"); p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false"); p.add("spring.config.import", () -> "");
    }
    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AnalysisRunStateService states;
    @Autowired CharacterFactComparisonJobCoordinator groups;
    @Autowired AnalysisFailureNotificationRepository notifications;
    @Autowired LegacyReceiver legacy;
    @MockitoBean ObjectStorage storage;
    final ObjectMapper json = new ObjectMapper();
    final Map<String, String> objects = new ConcurrentHashMap<>();
    record Fixture(UUID job, UUID owner, UUID retained, UUID origin, UUID incoming, UUID fact, UUID snapshotSource, String key, String evidence) {}
    record GroupFixture(UUID work, UUID job, List<UUID> originals, List<UUID> candidates) {}

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("첫 묶음 전 그룹 실패는 실제 대상 회차와 유일한 원본 출처를 불변 기록한다")
    void hiddenGroupFailureBeforeFirstBatchSnapshotsScope(int sourceCount) throws Exception {
        var f = seedGroup(sourceCount);
        String lease = claimGroup(f);
        assertThat(sql.queryForObject("SELECT count(*) FROM character_fact_comparison_batches WHERE analysis_job_id=?", Integer.class, f.job)).isZero();
        post("/api/internal/v1/analysis-jobs/"+f.job+"/fail", lease, groupFailure(), 200);
        var snapshot = groupJobSnapshot(f);
        assertThat(snapshot.path("target_episode_nos")).isEqualTo(json.readTree(sourceCount == 1 ? "[11]" : "[11,12]"));
        assertThat(snapshot.path("source_analysis_job_id").asText(null)).isEqualTo(sourceCount == 1 ? f.originals.getFirst().toString() : null);
        assertThat(snapshot.path("comparison_batch_id").isNull()).isTrue();
        assertThat(snapshot.path("execution_job_id").asText()).isEqualTo(f.job.toString());
        assertThat(snapshot.path("failure_diagnostics").path("reasonCode").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(snapshot.path("failure_code").asText()).isEqualTo("LLM_NETWORK_ERROR");
        assertThat(sql.queryForObject("SELECT count(*) FROM setting_candidates WHERE work_id=? AND comparison_status='FAILED' AND comparison_failure_analysis_job_id=?", Integer.class, f.work, f.job)).isEqualTo(2);
        sql.update("UPDATE setting_candidates SET comparison_status='PENDING',value_json='{\"value\":\"나중 값\"}'::jsonb WHERE work_id=?", f.work);
        sql.update("UPDATE episodes SET episode_no=episode_no+100 WHERE work_id=?", f.work);
        assertThat(groupJobSnapshot(f)).isEqualTo(snapshot);
        acknowledgeGroupEvents(f);
    }

    @Test
    @DisplayName("오래된 그룹 입력의 실패는 현재 후보를 바꾸거나 대상 범위를 추정하지 않는다")
    void staleHiddenGroupFailureDoesNotLinkCurrentCandidates() throws Exception {
        var f = seedGroup(2);
        String lease = claimGroup(f);
        sql.update("UPDATE setting_candidates SET value_json='{\"value\":\"변경된 입력\"}'::jsonb WHERE id=?", f.candidates.getLast());
        post("/api/internal/v1/analysis-jobs/"+f.job+"/fail", lease, groupFailure(), 200);
        assertThat(sql.queryForObject("SELECT count(*) FROM setting_candidates WHERE work_id=? AND comparison_status='PENDING' AND comparison_failure_analysis_job_id IS NULL", Integer.class, f.work)).isEqualTo(2);
        var snapshot = groupJobSnapshot(f);
        assertThat(snapshot.path("target_episode_nos").isNull()).isTrue();
        assertThat(snapshot.path("source_analysis_job_id").isNull()).isTrue();
        assertThat(sql.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE work_id=?", Integer.class, f.work)).isEqualTo(1);
        acknowledgeGroupEvents(f);
    }

    @Test
    @DisplayName("Job 실패 저장이 거절되면 먼저 저장한 그룹 후보 연결과 알림도 함께 롤백한다")
    void hiddenGroupFailureRollsBackFlushedCandidatesAndOutbox() throws Exception {
        var f = seedGroup(2);
        String lease = claimGroup(f);
        sql.execute("ALTER TABLE analysis_jobs ADD CONSTRAINT gh304_reject_group_failure CHECK (id <> '"+f.job+"'::uuid OR status <> 'FAILED')");
        try {
            post("/api/internal/v1/analysis-jobs/"+f.job+"/fail", lease, groupFailure(), 500);
            assertThat(sql.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, f.job)).isEqualTo("RUNNING");
            assertThat(sql.queryForObject("SELECT count(*) FROM setting_candidates WHERE work_id=? AND comparison_status='PENDING' AND comparison_failure_analysis_job_id IS NULL AND comparison_failure_diagnostics IS NULL", Integer.class, f.work)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE work_id=?", Integer.class, f.work)).isZero();
        } finally {
            sql.execute("ALTER TABLE analysis_jobs DROP CONSTRAINT gh304_reject_group_failure");
        }
    }

    @Test
    @DisplayName("첫 묶음 전 그룹의 lease 회수 한도 초과도 전체 대상 회차를 기록한다")
    void exhaustedHiddenGroupLeaseBeforeFirstBatchSnapshotsScope() throws Exception {
        var f = seedGroup(2);
        claimGroup(f);
        sql.update("UPDATE analysis_jobs SET claim_attempt_count=3,lease_expires_at=now()-interval '1 minute' WHERE id=?", f.job);
        assertThat(post("/api/internal/v1/analysis-jobs/claim", null, groupClaim(), 204).isNull()).isTrue();
        var snapshot = groupJobSnapshot(f);
        assertThat(snapshot.path("target_episode_nos")).isEqualTo(json.readTree("[11,12]"));
        assertThat(snapshot.path("source_analysis_job_id").isNull()).isTrue();
        assertThat(snapshot.path("failure_code").asText()).isEqualTo("WORKER_LEASE_EXPIRED");
        assertThat(sql.queryForObject("SELECT count(*) FROM setting_candidates WHERE work_id=? AND comparison_status='FAILED' AND comparison_failure_analysis_job_id=?", Integer.class, f.work, f.job)).isEqualTo(2);
        acknowledgeGroupEvents(f);
    }

    @Test
    @DisplayName("과거 인용만 확인 불가인 회차 비교는 HTTP 검토를 완료하고 실패 알림을 만들지 않는다")
    void episodeRetainedUnavailableCompletesThroughHttpWithoutFailureOutbox() throws Exception {
        var f = seed(true);
        var claim = post("/api/internal/v1/analysis-jobs/claim", null, Map.of(
                "allowedJobTypes", List.of("SETTING_EXTRACTION"), "supportedAnalysisModes", List.of("ORDERED_PROVISIONAL"),
                "supportedSourceKinds", List.of("EPISODE"), "supportsSemanticCurrentContext", true,
                "supportsFrozenComparisonEvidence", true), 200);
        assertThat(claim.path("analysisJobId").asText()).isEqualTo(f.job.toString());
        assertThat(claim.path("comparisonEvidenceContract").path("formatVersion").asInt()).isEqualTo(1);
        String lease = claim.path("leaseToken").asText();
        var batch = claimBatch(f, lease);
        String route = batchRoute(f, batch);
        var context = post(route+"/context", lease, null, 200);
        var current = context.path("advisoryCurrentContext").path("currentSlots").get(0);
        assertThat(current.path("factValue").asText()).isEqualTo("정상");
        var source = current.path("supportingSources").get(0);
        assertThat(source.path("availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(source.path("unavailableReason").asText()).isEqualTo("OFFSETS_UNAVAILABLE");
        assertThat(source.path("evidenceSpans")).isEmpty();
        var decision = json.createObjectNode().put("candidateRef", "C1").put("operation", "REVIEW_REQUIRED")
                .put("resolvedCanonicalFactKey", "profile.감각").put("temporalScope", "PRESENT")
                .put("comparisonReason", "합성 관측의 검토가 필요합니다.").put("advanceCurrentNarrativeTime", false);
        decision.putArray("removedSnapshotRefs"); decision.putArray("dependencyCandidateRefs");
        var complete = json.createObjectNode().put("contextToken", context.path("contextToken").asText());
        complete.putArray("decisions").add(decision); complete.putArray("failures");
        assertThat(events(f)).isZero();
        post(route+"/complete", lease, complete, 200);
        assertThat(sql.queryForObject("SELECT comparison_status||':'||suggested_operation FROM setting_candidates WHERE id=?", String.class, f.incoming))
                .isEqualTo("COMPLETED:REVIEW_REQUIRED");
        assertThat(sql.queryForObject("SELECT status='RUNNING' AND failure_code IS NULL AND source_kind='EPISODE' FROM analysis_jobs WHERE id=?", Boolean.class, f.job)).isTrue();
        assertThat(events(f)).isZero();
        assertPreserved(f);
    }

    @Test
    @DisplayName("실제 Python 실패 보고는 구 DTO 수신과 PostgreSQL·가짜 Discord까지 이어진다")
    @EnabledIfEnvironmentVariable(named = "GH304_AI_CHECKOUT", matches = ".+")
    void pythonReportsRealTypedSourceFailureAndLocalDiscordReceivesSnapshots() throws Exception {
        var f = seed(false);
        var claim = post("/api/internal/v1/analysis-jobs/claim", null, Map.of(
                "allowedJobTypes", List.of("SETTING_EXTRACTION"), "supportedSourceKinds", List.of("EPISODE")), 200);
        String lease = claim.path("leaseToken").asText();
        var batch = claimBatch(f, lease);
        // Corrupt only this synthetic retained source after claim, so Python observes the real context HTTP rejection.
        objects.put(f.key, "고정 해시와 다른 합성 바이트");
        Path checkout = Path.of(System.getenv("GH304_AI_CHECKOUT"));
        Path output = Files.createTempDirectory("gh304-python-http-");
        var builder = new ProcessBuilder(System.getenv("GH304_PYTHON"), "tests/support/comparison_evidence_http_client.py")
                .directory(checkout.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("python.log").toFile());
        builder.environment().putAll(Map.of("GH304_TEST_ONLY", "true", "PYTHONPATH", checkout.toString(),
                "SPRING_INTERNAL_API_BASE_URL", "http://127.0.0.1:"+port, "SPRING_INTERNAL_API_KEY", "synthetic-gh304-key"));
        for (String key : List.of("LLM_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_API_KEY")) builder.environment().remove(key);
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(json.writeValueAsBytes(Map.of("job", f.job, "batch", batch.path("comparisonBatchId").asText(),
                        "lease", lease, "sourceJob", f.origin, "sourceCandidate", f.retained)));
            }
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Python timeout; %s", output).isTrue();
            assertThat(process.exitValue()).as("Python log: %s", Files.readString(output.resolve("python.log"))).isZero();
        } finally { process.destroyForcibly(); }
        assertThat(legacy.received.stream().filter(id -> id.equals(f.job)).count()).isEqualTo(5);
        assertThat(events(f)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE work_id=(SELECT work_id FROM analysis_jobs WHERE id=?) AND failure_diagnostics->>'reasonCode'='CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH' AND failure_diagnostics->>'stage'='CHARACTER_CONTEXT' AND (failure_diagnostics->>'httpStatus')::int=409 AND execution_job_id=?", Integer.class, f.job, f.job)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT source_analysis_job_id FROM analysis_failure_notifications WHERE failure_kind='CHARACTER_COMPARISON' AND execution_job_id=?", UUID.class, f.job)).isEqualTo(f.job);
        assertThat(sql.queryForObject("SELECT failure_diagnostics->>'sourceAnalysisJobId' FROM analysis_failure_notifications WHERE failure_kind='CHARACTER_COMPARISON' AND execution_job_id=?", String.class, f.job)).isEqualTo(f.origin.toString());
        var bodies = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/synthetic-discord", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.start();
        try (var client = new DiscordWebhookClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/synthetic-discord"));
             var dispatcher = new AnalysisFailureNotificationDispatcher(notifications, client, new AnalysisFailureNotificationMapper(), "test")) {
            dispatcher.dispatchNext(); dispatcher.dispatchNext();
            for (int i=0;i<2;i++) {
                String body = bodies.poll(5, TimeUnit.SECONDS);
                assertThat(body).isNotNull();
                var wire = json.readTree(body);
                assertThat(wire.path("allowed_mentions").path("parse")).isEmpty();
                String message = wire.path("content").asText();
                assertThat(message).contains("CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", "HTTP: 409", "기존 캐릭터 비교 문맥 준비", f.origin.toString(), f.job.toString())
                        .doesNotContain("고정 해시와", "PRIVATE", "synthetic-gh304-key", "http://", "@everyone");
                assertThat(message.length()).isLessThanOrEqualTo(2000);
            }
        } finally { server.stop(0); }
        assertThat(sql.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE execution_job_id=? AND delivered_at IS NOT NULL", Integer.class, f.job)).isEqualTo(2);
        assertPreserved(f);
        // 실패 정리에서 예약한 이 fixture의 후속 그룹이 다음 테스트의 claim을 가로채지 않게 한다.
        sql.update("UPDATE analysis_jobs SET status='CANCELED' WHERE work_id=(SELECT work_id FROM analysis_jobs WHERE id=?) AND job_type='CHARACTER_FACT_COMPARISON' AND status='PENDING'", f.job);
    }

    @Test
    @DisplayName("구 Worker가 진단을 생략해도 승인된 비교 실행과 원래 근거를 보존한다")
    void oldWorkerFailurePayloadRetainsAuthorizedExecutionWithoutOptionalDiagnostic() throws Exception {
        var f = seed(false);
        var claim = post("/api/internal/v1/analysis-jobs/claim", null, Map.of("allowedJobTypes", List.of("SETTING_EXTRACTION")), 200);
        String lease = claim.path("leaseToken").asText();
        var batch = claimBatch(f, lease);
        post(batchRoute(f,batch)+"/fail", lease, Map.of("failureCode", "LLM_NETWORK_ERROR", "errorMessage", "synthetic legacy failure"), 200);
        assertThat(sql.queryForObject("SELECT comparison_failure_diagnostics IS NULL AND comparison_failure_analysis_job_id=? FROM setting_candidates WHERE id=?", Boolean.class, f.job, f.incoming)).isTrue();
        assertThat(sql.queryForObject("SELECT execution_job_id=? AND failure_diagnostics IS NULL FROM analysis_failure_notifications WHERE execution_job_id=?", Boolean.class, f.job, f.job)).isTrue();
        var event = notifications.claimNext().orElseThrow();
        assertThat(event.executionJobId()).isEqualTo(f.job);
        notifications.markDelivered(event);
        assertPreserved(f);
    }

    /** The old DTOs are copied from the exact baseline; Spring's actual HTTP converter must accept additive fields. */
    @org.springframework.boot.test.context.TestComponent
    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/api/internal/legacy/api/internal/v1/analysis-jobs/{job}")
    static class LegacyReceiver {
        final java.util.Queue<UUID> received = new java.util.concurrent.ConcurrentLinkedQueue<>();
        @org.springframework.web.bind.annotation.PostMapping("/fail")
        void job(@org.springframework.web.bind.annotation.PathVariable UUID job,
                 @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody org.monitoring.catchholebackend.support.legacy.analysis.WorkerAnalysisJobFailRequest body) {
            assertThat(body.failureCode()).isEqualTo(AnalysisFailureCode.UNEXPECTED_ERROR); received.add(job);
        }
        @org.springframework.web.bind.annotation.PostMapping({"/setting-candidates/{candidate}/character-fact-comparison-fail", "/character-fact-comparison-batches/{batch}/fail"})
        void character(@org.springframework.web.bind.annotation.PathVariable UUID job,
                 @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody org.monitoring.catchholebackend.support.legacy.character.WorkerCharacterFactComparisonFailRequest body) {
            assertThat(body.failureCode()).isEqualTo(AnalysisFailureCode.UNEXPECTED_ERROR); received.add(job);
        }
        @org.springframework.web.bind.annotation.PostMapping({"/world-setting-candidates/{candidate}/comparison-fail", "/world-setting-comparison-batches/{batch}/fail"})
        void world(@org.springframework.web.bind.annotation.PathVariable UUID job,
                 @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody org.monitoring.catchholebackend.support.legacy.worldsetting.WorkerWorldSettingComparisonFailRequest body) {
            assertThat(body.failureCode()).isEqualTo(AnalysisFailureCode.UNEXPECTED_ERROR); received.add(job);
        }
    }

    private Fixture seed(boolean ordered) throws Exception {
        when(storage.getText(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        return new TransactionTemplate(transactions).execute(status -> {
            var member = Member.register(UUID.randomUUID()+"@example.invalid", "test-only", null, "합성 작가"); em.persist(member);
            var work = Work.create(member, "합성 근거 검증", WorkGenre.FANTASY, null); em.persist(work);
            String key = "synthetic/"+work.getId(); String original = "감각은 “정상이다.”라고 적혔다."; objects.put(key, original);
            var oldEpisode = Episode.create(work, null, 1, "합성 이전 회차", key, "v1", hash(original), original.length()); em.persist(oldEpisode);
            var origin = AnalysisJob.create(work, null, oldEpisode, AnalysisJobType.SETTING_EXTRACTION); em.persist(origin); origin.succeed("{}", 0, 0);
            var owner = WorkCharacter.create(work, "합성 인물", null, null, null, null, null, null, null, null, null);
            var profile = json.createObjectNode(); profile.putObject("profile.감각").put("value", "정상");
            owner.replaceCurrentSnapshots(null, null, profile, null, null, null, null); em.persist(owner);
            var evidence = json.createArrayNode().add(json.createObjectNode().put("quote", "감각은 “정상이다.라고 적혔다."));
            var retained = SettingCandidate.create(work, oldEpisode, null, origin, SettingEntityType.CHARACTER, "합성 인물", "profile.감각", "정상", SettingValueType.STRING, json.createObjectNode().put("value", "정상"), evidence, BigDecimal.ONE, json.createObjectNode());
            ReflectionTestUtils.setField(retained, "matchedCharacterId", owner.getId()); retained.confirm(); em.persist(retained);
            var fact = CharacterFact.create(owner, retained, CharacterFactType.PROFILE, "profile.감각", "정상", "정상", json.createObjectNode().put("value", "정상"), oldEpisode, null, origin, BigDecimal.ONE, 1); em.persist(fact);
            var snapshotSource = CharacterSnapshotSource.create(owner, CharacterFactType.PROFILE, "profile.감각", fact, 0); em.persist(snapshotSource);
            em.persist(CharacterSettingSchema.create(work, "profile.감각", null, "감각", CharacterFactType.PROFILE, SettingValueType.STRING, CharacterSettingValueSemantics.BASE_VALUE, CharacterSettingMergePolicy.REPLACE, json.createArrayNode(), CharacterSettingSchemaSource.SYSTEM_SEED, true));
            String incoming = "😀 합성 인물의 감각이 저리다."; String incomingKey=key+"/incoming"; objects.put(incomingKey, incoming);
            var episode = Episode.create(work, null, 2, "합성 새 회차", incomingKey, "v1", hash(incoming), incoming.codePointCount(0,incoming.length())); em.persist(episode);
            var upload = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE); em.persist(upload);
            var job = AnalysisJob.create(work, upload, episode, AnalysisJobType.SETTING_EXTRACTION);
            if (ordered) { job.configureReviewMode(AnalysisReviewMode.AUTOMATIC); states.initializeRun(List.of(job)); }
            else em.persist(job);
            var candidate = SettingCandidate.create(work, episode, null, job, SettingEntityType.CHARACTER, "합성 인물", "합성 인물", owner.getId(), SettingCandidateMatchStatus.MATCHED, "profile.감각", "저림", SettingValueType.STRING, json.createObjectNode().put("value", "저림"), json.createArrayNode().add(json.createObjectNode().put("quote", incoming).put("startOffset", 0).put("endOffset", incoming.codePointCount(0,incoming.length()))), BigDecimal.ONE, json.createObjectNode()); em.persist(candidate);
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHARACTER_CANDIDATES_SAVED); em.flush();
            return new Fixture(job.getId(), owner.getId(), retained.getId(), origin.getId(), candidate.getId(), fact.getId(), snapshotSource.getId(), key, evidence.toString());
        });
    }
    private GroupFixture seedGroup(int sourceCount) {
        return new TransactionTemplate(transactions).execute(status -> {
            var member = Member.register(UUID.randomUUID()+"@example.invalid", "test-only", null, "합성 작가"); em.persist(member);
            var work = Work.create(member, "합성 그룹 실패", WorkGenre.FANTASY, null); em.persist(work);
            var upload = UploadBatch.create(work, member, sourceCount == 1 ? UploadType.SINGLE_EPISODE : UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE); em.persist(upload);
            var owner = WorkCharacter.create(work, "합성 인물", null, null, null, null, null, null, null, null, null); em.persist(owner);
            var originals = new ArrayList<AnalysisJob>();
            for (int i = 0; i < sourceCount; i++) {
                String text = "합성 인물의 관측 " + i;
                var episode = Episode.create(work, null, 11+i, "합성 회차", "synthetic/"+work.getId()+"/"+i, "v1", hash(text), text.length()); em.persist(episode);
                var original = AnalysisJob.create(work, upload, episode, AnalysisJobType.SETTING_EXTRACTION); em.persist(original); original.succeed("{}", 0, 0);
                originals.add(original);
            }
            var candidates = new ArrayList<SettingCandidate>();
            for (int i = 0; i < 2; i++) {
                var original = originals.get(i % sourceCount);
                var candidate = SettingCandidate.create(work, original.getEpisode(), null, original, SettingEntityType.CHARACTER, "합성 인물", "합성 인물", owner.getId(), SettingCandidateMatchStatus.MATCHED, i == 0 ? "profile.감각" : "profile.기력", "정상", SettingValueType.STRING, json.createObjectNode().put("value", "정상"), json.createArrayNode(), BigDecimal.ONE, json.createObjectNode()); em.persist(candidate);
                candidates.add(candidate);
            }
            var comparison = AnalysisJob.createCharacterFactComparison(candidates.getFirst(), groups.inputHash(candidates)); em.persist(comparison); em.flush();
            return new GroupFixture(work.getId(), comparison.getId(), originals.stream().map(AnalysisJob::getId).toList(), candidates.stream().map(SettingCandidate::getId).toList());
        });
    }
    private Map<String, Object> groupClaim() { return Map.of("allowedJobTypes", List.of("CHARACTER_FACT_COMPARISON"), "supportsCharacterComparisonGroups", true); }
    private String claimGroup(GroupFixture f) throws Exception {
        var claim = post("/api/internal/v1/analysis-jobs/claim", null, groupClaim(), 200);
        assertThat(claim.path("analysisJobId").asText()).isEqualTo(f.job.toString());
        return claim.path("leaseToken").asText();
    }
    private Map<String, Object> groupFailure() { return Map.of("failureCode", "LLM_NETWORK_ERROR", "errorMessage", "합성 초기 문맥 실패", "failureDiagnostics", Map.of("schemaVersion", 1, "origin", "PROVIDER", "stage", "CHARACTER_CONTEXT", "reasonCode", "LLM_TIMEOUT")); }
    private JsonNode groupJobSnapshot(GroupFixture f) throws Exception { return json.readTree(sql.queryForObject("SELECT row_to_json(n)::text FROM analysis_failure_notifications n WHERE failure_kind='ANALYSIS_JOB' AND execution_job_id=?", String.class, f.job)); }
    private void acknowledgeGroupEvents(GroupFixture f) { sql.update("UPDATE analysis_failure_notifications SET delivered_at=now() WHERE work_id=?", f.work); }
    private JsonNode claimBatch(Fixture f, String lease) throws Exception { return post("/api/internal/v1/analysis-jobs/"+f.job+"/character-fact-comparison-batches/claim-next", lease, null, 200); }
    private String batchRoute(Fixture f, JsonNode batch) { return "/api/internal/v1/analysis-jobs/"+f.job+"/character-fact-comparison-batches/"+batch.path("comparisonBatchId").asText(); }
    private JsonNode post(String route, String lease, Object body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route)).header("X-Internal-Api-Key", "synthetic-gh304-key").header("X-Character-Comparison-Person-Batches", "true").header("Content-Type", "application/json");
        if (lease != null) request.header("X-Worker-Lease-Token", lease);
        request.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("HTTP %s: %s", route, response.body()).isEqualTo(expected);
            return response.statusCode() == 204 ? json.getNodeFactory().nullNode() : json.readTree(response.body()).path("data");
        }
    }
    private int events(Fixture f) { return sql.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE work_id=(SELECT work_id FROM analysis_jobs WHERE id=?)", Integer.class, f.job); }
    private void assertPreserved(Fixture f) throws Exception {
        assertThat(sql.queryForObject("SELECT review_status FROM setting_candidates WHERE id=?", String.class, f.retained)).isEqualTo("CONFIRMED");
        assertThat(json.readTree(sql.queryForObject("SELECT evidence_spans::text FROM setting_candidates WHERE id=?", String.class, f.retained))).isEqualTo(json.readTree(f.evidence));
        assertThat(sql.queryForList("SELECT fact_value FROM character_facts WHERE character_id=?", String.class, f.owner)).containsExactly("정상");
        assertThat(sql.queryForList("SELECT id FROM character_facts WHERE character_id=?", UUID.class, f.owner)).containsExactly(f.fact);
        assertThat(sql.queryForList("SELECT id FROM character_snapshot_sources WHERE character_id=? AND source_fact_id=?", UUID.class, f.owner, f.fact)).containsExactly(f.snapshotSource);
    }
    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
