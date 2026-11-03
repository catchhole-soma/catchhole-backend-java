package org.monitoring.catchholebackend.domain.episode.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;
import org.monitoring.catchholebackend.domain.episode.dto.request.EpisodeTitleUpdateRequest;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.service.EpisodeService;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeAnalysisStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.monitoring.catchholebackend.domain.episode.repository.EpisodeAnalysisSummaryPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("test")
@Testcontainers
@Transactional
@DisplayName("PostgreSQL 회차 목록의 최신 분석 projection과 이력 보존")
class EpisodeAnalysisSummaryPostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "episode_summary_test")
            .withEnv("POSTGRES_USER", "episode_summary_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-episode-summary-test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/episode_summary_test");
        properties.add("spring.datasource.username", () -> "episode_summary_test");
        properties.add("spring.datasource.password", () -> "disposable-episode-summary-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    public static class SqlCapture implements StatementInspector {
        static final List<String> statements = new ArrayList<>();
        @Override public String inspect(String sql) { statements.add(sql); return sql; }
    }

    @Autowired EntityManager em;
    @Autowired EpisodeService episodes;
    @Autowired EpisodeAnalysisSummaryRepository summaries;
    Member member;
    Work work;
    UploadBatch batch;
    UploadFile sourceFile;

    @BeforeEach
    void setup() {
        member = Member.register(UUID.randomUUID() + "@local.invalid", "test-only", "01012345678", "합성 작가");
        em.persist(member);
        work = Work.create(member, "목록 조회 합성 작품", WorkGenre.FANTASY, null);
        em.persist(work);
        batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_SINGLE_FILE, UploadSourceType.FILE);
        em.persist(batch);
        sourceFile = UploadFile.create(batch, UploadFileRole.EPISODE, "합성 회차.txt", "text/plain", "s3://synthetic/upload", 100);
        em.persist(sourceFile);
    }

    @Test
    @DisplayName("최신 분석만 반환하고 후발 비교·batch-wide 작업과 다른 원본 배치는 제외한다")
    void selectsLatestRelevantJobInsideEachCurrentSourceBatch() {
        Episode first = episode(1);
        AnalysisJob old = job(first, batch, AnalysisJobType.EPISODE_VALIDATION);
        old.succeed("{\"unresolvedFindingCount\":9}", 0, 0);
        AnalysisJob latest = job(first, batch, AnalysisJobType.EPISODE_VALIDATION);
        latest.succeed("{\"findings\":[{},{}]}", 0, 0);
        job(first, batch, AnalysisJobType.CHARACTER_FACT_COMPARISON);
        job(first, batch, AnalysisJobType.WORLD_SETTING_COMPARISON);
        job(first, batch, AnalysisJobType.RELATIONSHIP_EXTRACTION);
        job(null, batch, AnalysisJobType.EPISODE_VALIDATION);
        UploadBatch replacedBatch = UploadBatch.create(work, member, UploadType.SINGLE_EPISODE, UploadSourceType.FILE);
        em.persist(replacedBatch);
        job(first, replacedBatch, AnalysisJobType.SETTING_EXTRACTION);
        em.flush();
        createdAt(old, 1);
        createdAt(latest, 2);
        resetReads();

        var response = episodes.getEpisodes(member.getId(), work.getId()).getFirst();
        assertThat(response.latestAnalysisJobId()).isEqualTo(latest.getId());
        assertThat(response.analysisStatus()).isEqualTo(EpisodeAnalysisStatus.COMPLETED);
        assertThat(response.unresolvedFindingCount()).isEqualTo(2);
        var rows = summaries.findLatestForEpisodes(work.getId(), List.of(batch.getId()), List.of(first.getId()));
        assertThat(rows).extracting(EpisodeAnalysisSummary::id).containsExactly(latest.getId());
        assertNoJobEntityOrHeavyJsonRead();
    }

    @Test
    @DisplayName("같은 최신 생성 시각은 임의 UUID 순서로 과거 계약을 바꾸지 않고 동률 행으로 남긴다")
    void retainsEqualLatestTimestampsWithoutIntroducingAnIdOrder() {
        Episode first = episode(1);
        AnalysisJob one = job(first, batch, AnalysisJobType.EPISODE_VALIDATION);
        AnalysisJob two = job(first, batch, AnalysisJobType.SETTING_EXTRACTION);
        em.flush();
        createdAt(one, 2);
        createdAt(two, 2);
        resetReads();
        assertThat(summaries.findLatestForEpisodes(work.getId(), List.of(batch.getId()), List.of(first.getId())))
                .extracting(EpisodeAnalysisSummary::id).containsExactlyInAnyOrder(one.getId(), two.getId());
        assertNoJobEntityOrHeavyJsonRead();
    }

    @ParameterizedTest
    @CsvSource({
            "SEALED,APPLIED,COMPLETED", "SEALED,HELD,COMPLETED", "SEALED,NONE,FAILED",
            "INCOMPLETE,NONE,FAILED", "INVALIDATED,APPLIED,REANALYSIS_REQUIRED"
    })
    @DisplayName("큰 과거 JSON을 보존하면서 목록과 제목 수정의 자동 반영·보류·무효화 상태가 같다")
    void preservesSummarySemanticsWithoutReadingStoredInputOrJournal(
            AnalysisJournalStatus journalStatus, String application, EpisodeAnalysisStatus expected) {
        Episode first = episode(1);
        AnalysisJob job = ordered(first, journalStatus, application);
        UUID jobId = job.getId();
        em.flush();
        Object original = storedJsonDigest(jobId);
        resetReads();

        var response = episodes.getEpisodes(member.getId(), work.getId()).getFirst();
        assertThat(response.latestAnalysisJobId()).isEqualTo(jobId);
        assertThat(response.analysisStatus()).isEqualTo(expected);
        assertThat(response.unresolvedFindingCount()).isEqualTo(expected == EpisodeAnalysisStatus.COMPLETED ? 3 : null);
        assertNoJobEntityOrHeavyJsonRead();

        resetReads();
        var changed = episodes.updateEpisodeTitle(member.getId(), work.getId(), first.getId(), new EpisodeTitleUpdateRequest("수정 제목"));
        assertThat(changed.latestAnalysisJobId()).isEqualTo(jobId);
        assertThat(changed.analysisStatus()).isEqualTo(expected);
        assertThat(changed.unresolvedFindingCount()).isEqualTo(response.unresolvedFindingCount());
        assertNoJobEntityOrHeavyJsonRead();
        assertThat(storedJsonDigest(jobId)).isEqualTo(original);
    }

    @Test
    @DisplayName("다른 회원은 요약을 읽기 전에 거절하며 보관된 회차를 다시 노출하지 않는다")
    void preservesOwnershipAndArchivedEpisodeFiltering() {
        Episode active = episode(1);
        Episode archived = episode(2);
        job(active, batch, AnalysisJobType.SETTING_EXTRACTION);
        job(archived, batch, AnalysisJobType.SETTING_EXTRACTION);
        archived.archive();
        resetReads();
        assertThatThrownBy(() -> episodes.getEpisodes(member.getId() + 1000, work.getId()))
                .isInstanceOf(AppException.class);
        assertThat(SqlCapture.statements).noneMatch(sql -> sql.contains("analysis_jobs"));
        assertThat(episodes.getEpisodes(member.getId(), work.getId()))
                .extracting(response -> response.id()).containsExactly(active.getId());
        assertNoJobEntityOrHeavyJsonRead();
    }

    private Episode episode(int number) {
        Episode episode = Episode.create(work, sourceFile.getId(), number, number + "화",
                "synthetic/episode/" + number, "v1", "a".repeat(64), 100);
        episode.markAnalyzed();
        em.persist(episode);
        return episode;
    }

    private AnalysisJob job(Episode episode, UploadBatch sourceBatch, AnalysisJobType type) {
        AnalysisJob job = AnalysisJob.create(work, sourceBatch, episode, type);
        em.persist(job);
        return job;
    }

    private AnalysisJob ordered(Episode episode, AnalysisJournalStatus status, String application) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        var input = JsonNodeFactory.instance.objectNode().put("retainedSyntheticEvidence", "합성 근거".repeat(100_000));
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, input);
        job.captureAutomaticInput(input);
        var journal = JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64));
        journal.putArray("changes").add(input);
        ReflectionTestUtils.setField(job, "inputStateHash", "c".repeat(64));
        ReflectionTestUtils.setField(job, "stateJournal", journal);
        ReflectionTestUtils.setField(job, "journalStatus", status);
        if (application.equals("APPLIED")) ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.now());
        if (application.equals("HELD")) ReflectionTestUtils.setField(job, "reviewApplicationHeldAt", LocalDateTime.now());
        job.succeed("{\"unresolvedFindingCount\":3}", 0, 0);
        em.persist(job);
        return job;
    }

    private void createdAt(AnalysisJob job, int day) {
        em.createNativeQuery("update analysis_jobs set created_at=:at where id=:id")
                .setParameter("at", LocalDateTime.of(2026, 11, day, 0, 0))
                .setParameter("id", job.getId()).executeUpdate();
    }

    private Object storedJsonDigest(UUID id) {
        return em.createNativeQuery("""
                select md5(automatic_input_state::text || run_base_state::text || state_journal::text)
                from analysis_jobs where id=:id
                """).setParameter("id", id).getSingleResult();
    }

    private void resetReads() {
        em.flush();
        em.clear();
        SqlCapture.statements.clear();
        em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics().clear();
    }

    private void assertNoJobEntityOrHeavyJsonRead() {
        assertThat(String.join("\n", SqlCapture.statements))
                .doesNotContain("automatic_input_state", "run_base_state", "state_journal");
        assertThat(em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics()
                .getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isZero();
    }
}
