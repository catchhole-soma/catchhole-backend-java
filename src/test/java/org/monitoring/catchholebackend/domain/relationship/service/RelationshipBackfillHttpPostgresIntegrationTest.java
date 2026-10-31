package org.monitoring.catchholebackend.domain.relationship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenAccount;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.auth.token.JwtTokenProvider;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipBackfillManifest;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.storage.ObjectStorage;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real Spring HTTP, PostgreSQL/Flyway and Python worker; only source storage/provider are synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "internal.api-key=synthetic-gh332-key",
        "analysis.relationship-backfill.enabled=true", "analysis.relationship-backfill.scheduling-enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "GH332_AI_CHECKOUT", matches = ".+")
@DisplayName("선정 사용자 관계 백필의 실제 Java·Python HTTP·PostgreSQL 연결")
class RelationshipBackfillHttpPostgresIntegrationTest {
    @Container static final GenericContainer<?> PG = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "relationship_test").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "relationship_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/relationship_test");
        p.add("spring.datasource.username", () -> "relationship_test");
        p.add("spring.datasource.password", () -> "test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RelationshipBackfillService coordinator;
    @Autowired ObjectStorageService storage;
    @Autowired JwtTokenProvider jwt;
    @MockitoBean ObjectStorage objects;
    @MockitoBean RelationshipBackfillManifest manifest;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> sources = Map.of(
            "synthetic/1", "아라와 보라는 함께 길을 떠났다.",
            "synthetic/2", "아라와 보라는 서로를 믿고 함께 싸웠다.",
            "synthetic/3", "아라는 보라를 걱정하며 함께 돌아왔다.");
    record Fixture(Long owner, UUID selectedWork, UUID excludedWork, String authorization) {}

    @Test
    @DisplayName("선정 작품의 1·2화와 도중 추가된 3화를 순서대로 반영하고 완성 전 공개하지 않는다")
    void selectedCohortAndNewTailCompleteThroughRealPythonWorker() throws Exception {
        when(objects.getText(anyString())).thenAnswer(call -> sources.get(call.getArgument(0)));
        Fixture fixture = seed();
        when(manifest.campaignKey()).thenReturn("relationship-backfill-20261030-v1");
        when(manifest.selectedAt()).thenReturn(LocalDateTime.now().plusMinutes(1));
        when(manifest.matches(any())).thenAnswer(call -> fixture.owner().equals(((Member) call.getArgument(0)).getId()));

        coordinator.bootstrapRelationshipBackfill();
        coordinator.bootstrapRelationshipBackfill();
        assertThat(count("relationship_backfill_works", fixture.selectedWork())).isEqualTo(1);
        assertThat(count("relationship_backfill_works", fixture.excludedWork())).isZero();
        assertThat(count("relationship_backfill_items", fixture.selectedWork())).isEqualTo(2);
        assertThat(graph(fixture, null).path("edges")).isEmpty();

        runWorker(fixture, 1, true);
        assertThat(state(fixture)).isEqualTo("BUILDING:0");
        assertThat(graph(fixture, null).path("edges")).isEmpty();
        addCompletedCoreTail(fixture);
        runWorker(fixture, 2, true);
        assertThat(state(fixture)).isEqualTo("BUILDING:0");
        assertThat(graph(fixture, null).path("edges")).isEmpty();
        runWorker(fixture, 3, true);
        runWorker(fixture, 0, false);

        assertThat(state(fixture)).isEqualTo("COMPLETE:1");
        assertThat(sql.queryForList("select episode_no from relationship_backfill_items where work_id=? and state='SUCCEEDED' order by episode_no", Integer.class, fixture.selectedWork()))
                .containsExactly(1, 2, 3);
        assertThat(count("relationship_backfill_items", fixture.excludedWork())).isZero();
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=? and job_type='RELATIONSHIP_EXTRACTION' and status='SUCCEEDED'", Integer.class, fixture.selectedWork()))
                .isEqualTo(3);
        assertThat(sql.queryForObject("select count(*) from analysis_jobs where work_id=?", Integer.class, fixture.excludedWork())).isZero();
        assertThat(sql.queryForObject("select count(*) from episodes where work_id=? and status='ANALYZED'", Integer.class, fixture.selectedWork())).isEqualTo(3);

        // Each actual Python worker uses only extraction + comparison (100 input + 10 output each).
        assertThat(sql.queryForObject("select count(*) from ai_token_usages where work_id=? and purpose in ('RELATIONSHIP_EXTRACTION','RELATIONSHIP_COMPARISON') and status='SETTLED' and model_name='gpt-6.1-terra'", Integer.class, fixture.selectedWork())).isEqualTo(6);
        assertThat(sql.queryForObject("select sum(input_tokens+output_tokens) from ai_token_usages where work_id=?", Long.class, fixture.selectedWork())).isEqualTo(660L);
        assertThat(sql.queryForObject("select sum(u.charged_tokens) from ai_token_usages u join analysis_jobs j on j.id=u.analysis_job_id where u.work_id=? and j.relationship_service_paid", BigDecimal.class, fixture.selectedWork())).isZero();
        assertThat(sql.queryForObject("select used_tokens from ai_token_accounts where member_id=?", BigDecimal.class, fixture.owner())).isEqualByComparingTo("220");
        assertThat(sql.queryForObject("select reserved_tokens from ai_token_accounts where member_id=?", BigDecimal.class, fixture.owner())).isZero();
        for (String table : List.of("characters", "setting_candidates", "world_settings", "world_setting_candidates")) {
            assertThat(count(table, fixture.selectedWork())).as("relation worker must not create %s", table).isZero();
        }
        assertThat(sql.queryForObject("select count(*) from episode_chunks c join episodes e on e.id=c.episode_id where e.work_id=?", Integer.class, fixture.selectedWork())).isZero();
        for (int episode : List.of(1, 2, 3)) {
            JsonNode graph = graph(fixture, episode);
            assertThat(graph.path("edges").size()).isEqualTo(1);
            assertThat(graph.path("nodes").size()).isEqualTo(2);
        }
    }

    private Fixture seed() {
        return new TransactionTemplate(transactions).execute(status -> {
            Member selected = Member.register(UUID.randomUUID() + "@synthetic.invalid", "test-only", null, "선정 작가");
            Member excluded = Member.register(UUID.randomUUID() + "@synthetic.invalid", "test-only", null, "미선정 작가");
            em.persist(selected); em.persist(excluded);
            Work work = Work.create(selected, "합성 관계 백필", WorkGenre.FANTASY, null);
            Work other = Work.create(excluded, "미선정 합성 작품", WorkGenre.FANTASY, null);
            em.persist(work); em.persist(other);
            em.persist(AiTokenAccount.create(selected, 100_000));
            for (Work target : List.of(work, other)) for (int no : List.of(1, 2)) episode(target, no);
            em.flush();
            return new Fixture(selected.getId(), work.getId(), other.getId(), "Bearer " + jwt.generateAccessToken(selected));
        });
    }

    private Episode episode(Work work, int no) {
        String source = sources.get("synthetic/" + no);
        Episode episode = Episode.create(work, null, no, no + "화", "synthetic/" + no, "v1", storage.contentHash(source), source.length());
        episode.markAnalyzed();
        em.persist(episode);
        return episode;
    }

    private void addCompletedCoreTail(Fixture fixture) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Work work = em.find(Work.class, fixture.selectedWork());
            Episode episode = episode(work, 3);
            AnalysisJob core = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
            core.claim("synthetic-core", "합성 완료 경계", LocalDateTime.now().plusMinutes(5));
            core.succeed("{}", 0, 0);
            em.persist(core);
            coordinator.enqueueCompletedCoreAnalysis(core);
        });
    }

    private int count(String table, UUID work) {
        return sql.queryForObject("select count(*) from " + table + " where work_id=?", Integer.class, work);
    }

    private String state(Fixture fixture) {
        return sql.queryForObject("select state||':'||published_generation from relationship_backfill_works where work_id=?", String.class, fixture.selectedWork());
    }

    private JsonNode graph(Fixture fixture, Integer episode) throws Exception {
        String path = "/api/v1/works/" + fixture.selectedWork() + "/relationship-graph" + (episode == null ? "" : "?episodeNo=" + episode);
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", fixture.authorization()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("graph response: %s", response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }

    private void runWorker(Fixture fixture, int episode, boolean expectedClaim) throws Exception {
        String python = System.getenv("GH332_PYTHON");
        assertThat(python).as("GH332_PYTHON must point to a prepared test interpreter").isNotBlank();
        Path checkout = Path.of(System.getenv("GH332_AI_CHECKOUT"));
        Path output = Files.createTempDirectory("gh332-relationship-http-");
        var builder = new ProcessBuilder(python, "tests/support/relationship_backfill_http_client.py")
                .directory(checkout.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("python.log").toFile());
        builder.environment().putAll(Map.of("GH332_TEST_ONLY", "true", "PYTHONPATH", checkout.toString(),
                "SPRING_INTERNAL_API_BASE_URL", "http://127.0.0.1:" + port, "SPRING_INTERNAL_API_KEY", "synthetic-gh332-key"));
        for (String key : List.of("LLM_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_API_KEY", "DATABASE_URL", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")) builder.environment().remove(key);
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(json.writeValueAsBytes(Map.of("workId", fixture.selectedWork(), "expectedEpisodeNo", episode,
                        "expectedClaim", expectedClaim, "sources", sources)));
            }
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("Python timeout; %s", output).isTrue();
            assertThat(process.exitValue()).as("Python log: %s", Files.readString(output.resolve("python.log"))).isZero();
        } finally {
            process.destroyForcibly();
        }
    }
}
