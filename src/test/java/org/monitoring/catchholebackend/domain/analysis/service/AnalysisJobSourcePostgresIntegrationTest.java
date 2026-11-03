package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobSourceRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.monitoring.catchholebackend.support.JdbcResultMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@DirtiesContext
@Transactional
@Testcontainers(disabledWithoutDocker = true)
@Import(AnalysisJobSourcePostgresIntegrationTest.MetricsConfiguration.class)
@DisplayName("과거 Job 메타데이터와 미확정 참고의 PostgreSQL 조회 경계")
class AnalysisJobSourcePostgresIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh374_source_test").withEnv("POSTGRES_USER", "gh374_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-gh374-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/gh374_source_test");
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
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisJobSourceRepository sources;
    @Autowired AnalysisPendingReferenceSource pending;
    @Autowired AnalysisHumanRejectionPolicy rejections;

    @Test
    @DisplayName("최신 실패·종료·파기·수정 여부를 유지하며 이전 JSON과 제외된 후보를 읽지 않는다")
    void pendingCapturePreservesSelectionAndDoesNotReadPastJobJson() {
        Work work = work();
        AnalysisJob valid = job(work, 1);
        SettingCandidate character = character(valid);
        WorldSettingCandidate world = world(valid);
        SettingCandidate closed = character(valid);
        closed.closeForContinuation(UUID.randomUUID());
        WorldSettingCandidate confirmed = world(valid);
        ReflectionTestUtils.setField(confirmed, "reviewStatus", WorldSettingReviewStatus.CONFIRMED);
        AnalysisJob superseded = job(work, 2);
        character(superseded);
        AnalysisJob newerFailed = AnalysisJob.create(work, null, superseded.getEpisode(), AnalysisJobType.SETTING_EXTRACTION);
        ReflectionTestUtils.setField(newerFailed, "status", AnalysisJobStatus.FAILED);
        em.persist(newerFailed);
        AnalysisJob edited = job(work, 3);
        ReflectionTestUtils.setField(edited, "journalStatus", AnalysisJournalStatus.INVALIDATED);
        SettingCandidate manual = character(edited);
        ReflectionTestUtils.setField(manual, "userModified", true);
        character(edited);
        AnalysisJob archived = job(work, 4);
        character(archived);
        ReflectionTestUtils.setField(archived.getEpisode(), "status", EpisodeStatus.ARCHIVED);
        AnalysisJob replacedSource = job(work, 5);
        character(replacedSource);
        ReflectionTestUtils.setField(replacedSource.getEpisode(), "contentS3Key", "replacement/source");
        AnalysisJob current = job(work, 7);
        UUID currentId = current.getId(), validId = valid.getId();
        var expected = List.of("pending-character:" + character.getId(), "pending-world:" + world.getId(), "pending-character:" + manual.getId());
        em.flush();
        jdbc.update("update analysis_jobs set created_at='2026-01-01' where id=?", superseded.getId());
        jdbc.update("update analysis_jobs set created_at='2026-01-02' where id=?", newerFailed.getId());
        String before = stateDigest(validId);
        em.clear();
        current = em.find(AnalysisJob.class, currentId);
        AnalysisJob unloaded = em.getReference(AnalysisJob.class, validId);
        assertThat(Hibernate.isInitialized(unloaded)).isFalse();
        JdbcResultMetrics.start();
        com.fasterxml.jackson.databind.JsonNode references;
        List<JdbcResultMetrics.QueryResult> queries;
        try { references = pending.capture(current); }
        finally { queries = JdbcResultMetrics.stop(); }
        assertThat(references.properties().stream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(references.path("pending-character:" + character.getId()).path("evidenceSpans").get(0).path("quote").asText()).isEqualTo("보존할 합성 근거");
        assertThat(Hibernate.isInitialized(unloaded)).isFalse();
        assertNoJobJson(queries);
        var candidateQueries = queries.stream().filter(query -> query.sql().contains(" from setting_candidates ") || query.sql().contains(" from world_setting_candidates ")).toList();
        assertThat(candidateQueries).hasSize(2);
        assertThat(candidateQueries.stream().mapToLong(JdbcResultMetrics.QueryResult::consumedRows).sum()).isEqualTo(4);
        assertThat(stateDigest(validId)).isEqualTo(before);
    }

    @Test
    @DisplayName("반려 이력을 계승해도 과거 Job JSON을 초기화하지 않고 동일 원문 주장 해시를 유지한다")
    void rejectedClaimsCapturePreservesFingerprintsWithoutHydratingPastJobs() {
        Work work = work();
        AnalysisJob characterJob = job(work, 1);
        AnalysisJob worldJob = job(work, 2);
        var owner = org.monitoring.catchholebackend.domain.character.entity.WorkCharacter.create(work,
                "반려 대상 인물", null, null, null, JSON.objectNode(), JSON.objectNode(), JSON.arrayNode(),
                JSON.arrayNode(), JSON.arrayNode(), characterJob.getEpisode().getId());
        em.persist(owner);
        SettingCandidate character = character(characterJob);
        character.matchExistingCharacter(owner);
        ((com.fasterxml.jackson.databind.node.ObjectNode) character.getEvidenceSpans().get(0)).put("startOffset", 0).put("endOffset", 12);
        character.recordUserModification();
        character.dismiss();
        WorldSettingCandidate world = world(worldJob);
        ((com.fasterxml.jackson.databind.node.ObjectNode) world.getEvidenceSpans().get(0)).put("startOffset", 0).put("endOffset", 12);
        ReflectionTestUtils.setField(world, "comparisonStatus", WorldSettingComparisonStatus.PENDING);
        UUID worldTarget = UUID.randomUUID();
        world.resolveOrderedSubject(org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType.EXISTING,
                "world:" + worldTarget, "합성 장소", JSON.arrayNode().add(worldTarget.toString()), JSON.arrayNode());
        world.dismiss("합성 반려", work.getMember());
        UUID workId = work.getId(), characterJobId = characterJob.getId(), worldJobId = worldJob.getId();
        UUID characterId = character.getId(), worldId = world.getId();
        em.flush();
        String beforeCharacter = stateDigest(characterJobId), beforeWorld = stateDigest(worldJobId);
        em.clear();
        AnalysisJob unloadedCharacter = em.getReference(AnalysisJob.class, characterJobId);
        AnalysisJob unloadedWorld = em.getReference(AnalysisJob.class, worldJobId);
        JdbcResultMetrics.start();
        com.fasterxml.jackson.databind.JsonNode captured;
        List<JdbcResultMetrics.QueryResult> queries;
        try { captured = rejections.capture(em.getReference(Work.class, workId)); }
        finally { queries = JdbcResultMetrics.stop(); }
        assertThat(captured.size()).isEqualTo(2);
        assertThat(Hibernate.isInitialized(unloadedCharacter)).isFalse();
        assertThat(Hibernate.isInitialized(unloadedWorld)).isFalse();
        assertNoJobJson(queries);
        var state = JSON.objectNode().set("references", captured);
        // Matching still reads the live entity; its fingerprint must equal the scalar capture's fingerprint.
        assertThat(rejections.match(em.find(SettingCandidate.class, characterId), state)).isPresent();
        assertThat(rejections.match(em.find(WorldSettingCandidate.class, worldId), state)).isPresent();
        assertThat(stateDigest(characterJobId)).isEqualTo(beforeCharacter);
        assertThat(stateDigest(worldJobId)).isEqualTo(beforeWorld);
    }

    @Test
    @DisplayName("설정집 revision 출처·현재 버전과 실행 상태는 엔티티 계약과 같고 JSON은 선택하지 않는다")
    void sourceProjectionPreservesBookAndEpisodeContracts() {
        Work work = work();
        AnalysisJob episode = job(work, 1);
        UploadBatch batch = UploadBatch.create(work, work.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE);
        em.persist(batch);
        UploadFile book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "합성 설정집.txt", "text/plain", "synthetic/book", 4);
        em.persist(book);
        SettingBookRevision revision = SettingBookRevision.create(book, 1,
                new StoredTextObject("synthetic/revision", "v1", "b".repeat(64), 4), SettingBookReferenceTimeKind.EPISODE, 2);
        em.persist(revision);
        book.linkCurrentRevision(revision);
        AnalysisJob bookJob = AnalysisJob.createSettingBook(work, book, revision);
        bookJob.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JSON.objectNode());
        em.persist(bookJob);
        em.flush();
        var expectedBook = JSON.objectNode();
        AnalysisSourceMetadata.put(expectedBook, bookJob);
        var expectedEpisode = JSON.objectNode();
        AnalysisSourceMetadata.put(expectedEpisode, episode);
        UUID bookJobId = bookJob.getId(), episodeId = episode.getId(), bookId = book.getId();
        em.clear();
        JdbcResultMetrics.start();
        List<JdbcResultMetrics.QueryResult> queries;
        try {
            var bookView = sources.findSourceById(bookJobId).orElseThrow();
            var episodeView = sources.findSourceById(episodeId).orElseThrow();
            var actualBook = JSON.objectNode();
            var actualEpisode = JSON.objectNode();
            AnalysisSourceMetadata.put(actualBook, bookView);
            AnalysisSourceMetadata.put(actualEpisode, episodeView);
            assertThat(actualBook).isEqualTo(expectedBook);
            assertThat(actualEpisode).isEqualTo(expectedEpisode);
            assertThat(bookView.hasCurrentSourceVersion()).isTrue();
            assertThat(episodeView.hasCurrentSourceVersion()).isTrue();
            assertThat(sources.findSettingBookExtractionJobs(work.getId())).extracting(row -> row.getId()).containsExactly(bookJobId);
            assertThat(sources.findRunMembers(episodeView.getAnalysisRunId(), episodeView.getRunGeneration())).extracting(row -> row.getId()).containsExactly(episodeId);
        } finally { queries = JdbcResultMetrics.stop(); }
        assertNoJobJson(queries);
        jdbc.update("update upload_files set archived_at=now() where id=?", bookId);
        assertThat(sources.findSourceById(bookJobId).orElseThrow().hasCurrentSourceVersion()).isFalse();
    }

    private Work work() {
        Member member = Member.register("source-" + UUID.randomUUID() + "@example.invalid", "test-only", null, "합성 작가");
        em.persist(member);
        Work work = Work.create(member, "합성 작품", WorkGenre.FANTASY, "");
        em.persist(work);
        return work;
    }

    private AnalysisJob job(Work work, int episodeNo) {
        Episode episode = Episode.create(work, null, episodeNo, "합성 회차", "synthetic/" + episodeNo, "v1", "a".repeat(64), 8);
        em.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JSON.objectNode().put("retained", "x".repeat(32768)));
        job.captureAutomaticInput(JSON.objectNode().put("retained", "a".repeat(32768)));
        job.prepareOrderedInput("a".repeat(64));
        job.replacePendingJournal(JSON.objectNode().put("formatVersion", 1).put("outputStateHash", "b".repeat(64)).put("retained", "j".repeat(32768)).set("changes", JSON.arrayNode()));
        job.claim("synthetic", "synthetic", java.time.LocalDateTime.now().plusMinutes(5));
        job.sealJournal();
        job.completeAutomaticApplication();
        job.succeed("{}", 0, 0);
        em.persist(job);
        return job;
    }

    private SettingCandidate character(AnalysisJob job) {
        var evidence = JSON.arrayNode();
        evidence.addObject().put("quote", "보존할 합성 근거");
        SettingCandidate candidate = SettingCandidate.create(job.getWork(), job.getEpisode(), null, job,
                SettingEntityType.CHARACTER, "합성 인물", "profile.특징", "보존할 값", SettingValueType.STRING,
                JSON.objectNode().put("value", "보존할 값"), evidence,
                BigDecimal.ONE, JSON.objectNode());
        ReflectionTestUtils.setField(candidate, "comparisonStatus", CharacterFactComparisonStatus.COMPLETED);
        em.persist(candidate);
        return candidate;
    }

    private WorldSettingCandidate world(AnalysisJob job) {
        var evidence = JSON.arrayNode();
        evidence.addObject().put("quote", "보존할 합성 근거");
        WorldSettingCandidate candidate = WorldSettingCandidate.create(job.getWork(), job.getEpisode(), job,
                WorldSettingCategory.LOCATION, "합성 장소", "색", "파랑", evidence, BigDecimal.ONE, JSON.objectNode());
        ReflectionTestUtils.setField(candidate, "comparisonStatus", WorldSettingComparisonStatus.COMPLETED);
        em.persist(candidate);
        return candidate;
    }

    private String stateDigest(UUID jobId) {
        return jdbc.queryForObject("select md5(automatic_input_state::text || run_base_state::text || state_journal::text) from analysis_jobs where id=?", String.class, jobId);
    }

    private void assertNoJobJson(List<JdbcResultMetrics.QueryResult> queries) {
        assertThat(queries).isNotEmpty();
        assertThat(queries.stream().flatMap(query -> query.selectedColumns().stream()).toList())
                .doesNotContain("automatic_input_state", "run_base_state", "state_journal");
    }
}
