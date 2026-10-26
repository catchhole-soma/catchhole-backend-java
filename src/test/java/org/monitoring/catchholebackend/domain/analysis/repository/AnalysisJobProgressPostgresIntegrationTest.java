package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.monitoring.catchholebackend.support.AnalysisProgressApiFixture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobProgressPostgresIntegrationTest$SqlCapture"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("PostgreSQL 진행 조회의 scalar SQL과 이력 계약")
class AnalysisJobProgressPostgresIntegrationTest extends AnalysisProgressApiFixture {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "analysis_progress_test").withEnv("POSTGRES_USER", "analysis_progress_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-progress-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2).withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/analysis_progress_test");
        properties.add("spring.datasource.username", () -> "analysis_progress_test");
        properties.add("spring.datasource.password", () -> "disposable-progress-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    public static class SqlCapture implements StatementInspector {
        static final List<String> statements = new ArrayList<>();
        @Override public String inspect(String sql) { statements.add(sql); return sql; }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    @DisplayName("작업 수와 무관하게 두 projection만 읽고 큰 JSON이나 엔티티를 로드하지 않는다")
    void usesConstantScalarReads(int count) throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            var job = AnalysisJob.create(work, batch, episode(n), AnalysisJobType.SETTING_EXTRACTION);
            job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
            job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
            ReflectionTestUtils.setField(job, "automaticInputState", JsonNodeFactory.instance.objectNode().put("synthetic", "x".repeat(100_000)));
            em.persist(job); ids.add(job.getId());
        }
        clearReadStatistics();
        assertThat(read(ids)).hasSize(count);
        assertScalarReads(3);
    }

    @Test
    @DisplayName("스냅샷 복수 대상과 보관 회차를 우선하고 없을 때만 단일 episode로 복원한다")
    void preservesSnapshotsArchivedFallbackAndTargetlessJobs() throws Exception {
        var snapshot = job(1); var second = episode(2); var archived = episode(3);
        ReflectionTestUtils.setField(archived, "status", EpisodeStatus.ARCHIVED);
        ReflectionTestUtils.setField(snapshot, "targetEpisodes", new LinkedHashSet<>(List.of(archived, second)));
        var legacy = job(4); ReflectionTestUtils.setField(legacy, "targetEpisodes", new LinkedHashSet<>());
        var targetless = AnalysisJob.create(work, batch, null, AnalysisJobType.EPISODE_VALIDATION); em.persist(targetless);
        clearReadStatistics();
        var rows = read(List.of(snapshot.getId(), legacy.getId(), targetless.getId()));
        assertThat(rows.get(0).path("episodes").get(0).path("episodeNo").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("episodes").get(1).path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(rows.get(0).path("episodes")).hasSize(2);
        assertThat(rows.get(1).path("episodes").get(0).path("episodeNo").asInt()).isEqualTo(4);
        assertThat(rows.get(2).path("episodes")).isEmpty();
        assertScalarReads(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARACTER_COMPARISONS_FINISHED", "WORLD_CANDIDATES_PUBLISHED", "WORLD_COMPARISONS_FINISHED"})
    @DisplayName("기존 일반 모드의 추출 보존 토큰 중단 checkpoint를 정확히 계산한다")
    void mapsTokenCheckpoint(String checkpoint) throws Exception {
        var job = job(1); job.updateCheckpointStage(AnalysisJobCheckpointStage.valueOf(checkpoint));
        job.fail(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, "synthetic");
        clearReadStatistics();
        var row = read(List.of(job.getId())).get(0);
        assertThat(row.path("tokenInterruptedAfterExtraction").asBoolean()).isEqualTo(!checkpoint.equals("CHARACTER_COMPARISONS_FINISHED"));
        assertThat(row.path("failureCode").asText()).isEqualTo("AI_TOKEN_QUOTA_EXHAUSTED");
    }

    @Test
    @DisplayName("순차 실행 메타데이터와 저장 latch를 보존하고 누락 실패 코드는 공개 unexpected로 정규화한다")
    void mapsRunLatchAndPublicFailure() throws Exception {
        var first = AnalysisJob.create(work, batch, episode(1), AnalysisJobType.SETTING_EXTRACTION); first.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        UUID run = UUID.randomUUID(); first.initializeOrderedRun(run, 2, 0, null, JsonNodeFactory.instance.objectNode());
        ReflectionTestUtils.setField(first, "status", AnalysisJobStatus.SUCCEEDED);
        ReflectionTestUtils.setField(first, "journalStatus", AnalysisJournalStatus.SEALED);
        ReflectionTestUtils.setField(first, "inputStateHash", "b".repeat(64));
        ReflectionTestUtils.setField(first, "stateJournal", JsonNodeFactory.instance.objectNode().put("outputStateHash", "c".repeat(64)).set("changes", JsonNodeFactory.instance.arrayNode()));
        ReflectionTestUtils.setField(first, "failureCode", AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED);
        em.persist(first);
        var failed = job(2); failed.fail(null, "private failure is not returned"); ReflectionTestUtils.setField(failed, "failureCode", null);
        clearReadStatistics();
        var rows = read(List.of(first.getId(), failed.getId()));
        assertThat(rows.get(0).path("analysisRun").path("runId").asText()).isEqualTo(run.toString());
        assertThat(rows.get(0).path("analysisRun").path("generation").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("analysisRun").path("journalStatus").asText()).isEqualTo("SEALED");
        assertThat(rows.get(0).path("automaticAppliedAt").isNull()).isTrue();
        assertThat(rows.get(0).path("failureCode").isNull()).isTrue();
        assertThat(rows.get(1).path("failureCode").asText()).isEqualTo("UNEXPECTED_ERROR");
        assertScalarReads(3);
    }

    @Test
    @DisplayName("세계관 세 집계는 같은 회차 배치의 검토 대기만 세고 payload 엔티티를 읽지 않는다")
    void comparisonCountsKeepSourceBatchReviewAndFailureScope() throws Exception {
        var origin = job(1);
        candidate(origin, "PENDING_REVIEW", "PENDING", null);
        candidate(origin, "PENDING_REVIEW", "PROCESSING", null);
        candidate(origin, "PENDING_REVIEW", "FAILED", "AI_TOKEN_QUOTA_EXHAUSTED");
        candidate(origin, "PENDING_REVIEW", "FAILED", "UNEXPECTED_ERROR");
        candidate(origin, "CONFIRMED", "PENDING", null);
        var anotherBatch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE); em.persist(anotherBatch);
        var otherOrigin = AnalysisJob.create(work, anotherBatch, episode(2), AnalysisJobType.SETTING_EXTRACTION); em.persist(otherOrigin);
        candidate(otherOrigin, "PENDING_REVIEW", "PENDING", null);
        var book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "합성.txt", "text/plain", "s3://synthetic/book", 100); em.persist(book);
        var revision = SettingBookRevision.create(book, 1, new StoredTextObject("synthetic/book", "v1", "d".repeat(64), 100)); em.persist(revision); book.linkCurrentRevision(revision);
        var bookOrigin = AnalysisJob.createSettingBook(work, book, revision);
        bookOrigin.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode()); em.persist(bookOrigin);
        candidate(bookOrigin, "PENDING_REVIEW", "PROCESSING", null);
        clearReadStatistics();
        var result = mvc.perform(get(comparisonUrl()).header("Authorization", authorization).queryParam("batchId", batch.getId().toString())).andExpect(status().isOk()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("pendingComparisonCount", "processingComparisonCount", "tokenInterruptedComparisonCount");
        assertThat(data.path("pendingComparisonCount").asLong()).isEqualTo(1);
        assertThat(data.path("processingComparisonCount").asLong()).isEqualTo(1);
        assertThat(data.path("tokenInterruptedComparisonCount").asLong()).isEqualTo(1);
        assertScalarReads(3);
    }

    private JsonNode read(List<UUID> ids) throws Exception {
        var result = mvc.perform(get(progressUrl()).header("Authorization", authorization)
                .queryParam("analysisJobIds", ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void clearReadStatistics() {
        em.flush(); em.clear(); SqlCapture.statements.clear();
        em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics().clear();
    }

    private void assertScalarReads(int expected) {
        // JWT 인증의 회원 1회 + 작품 소유권 1회 + 상태·대상 projection 2회다.
        assertThat(SqlCapture.statements).hasSize(expected + 1);
        assertThat(String.join("\n", SqlCapture.statements).toLowerCase()).doesNotContain("automatic_input_state", "run_base_state", "state_journal", "summary_json", "content_s3_key", "evidence_spans", "raw_extraction_json");
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        for (Class<?> type : List.of(AnalysisJob.class, Episode.class, WorldSettingCandidate.class)) {
            assertThat(statistics.getEntityStatistics(type.getName()).getLoadCount()).as(type.getSimpleName()).isZero();
        }
    }

    private void candidate(AnalysisJob origin, String review, String comparison, String failure) {
        em.flush();
        em.createNativeQuery("""
                insert into world_setting_candidates(id,work_id,analysis_job_id,source_kind,source_setting_book_id,
                source_setting_book_revision_id,source_episode_id,category,subject_name,setting_name,extracted_value,evidence_spans,
                review_status,comparison_status,comparison_failure_code,created_at,updated_at)
                values(:id,:work,:job,:source,:book,:revision,:episode,'LOCATION','합성 장소','특징','합성 값',cast('[]' as jsonb),
                :review,:comparison,:failure,current_timestamp,current_timestamp)
                """).setParameter("id", UUID.randomUUID()).setParameter("work", work.getId()).setParameter("job", origin.getId())
                .setParameter("source", origin.getSourceKind().name()).setParameter("book", origin.isSettingBookSource() ? origin.getSourceSettingBook().getId() : null)
                .setParameter("revision", origin.isSettingBookSource() ? origin.getSourceSettingBookRevision().getId() : null)
                .setParameter("episode", origin.getEpisode() == null ? null : origin.getEpisode().getId())
                .setParameter("review", review).setParameter("comparison", comparison).setParameter("failure", failure).executeUpdate();
    }
}
