package org.monitoring.catchholebackend.domain.analysis.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.type.*;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
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
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.monitoring.catchholebackend.domain.analysis.repository.AnalysisBatchSummaryPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("test")
@Testcontainers
@Transactional
@DisplayName("PostgreSQL 분석 목록의 최신 선택과 작은 카드 요약")
class AnalysisBatchSummaryPostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "analysis_summary_test").withEnv("POSTGRES_USER", "analysis_summary_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-summary-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/analysis_summary_test");
        p.add("spring.datasource.username", () -> "analysis_summary_test");
        p.add("spring.datasource.password", () -> "disposable-summary-test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }
    public static class SqlCapture implements StatementInspector {
        static final List<String> statements = new ArrayList<>();
        @Override public String inspect(String sql) { statements.add(sql); return sql; }
    }
    @Autowired EntityManager em;
    @Autowired AnalysisJobService service;
    @Autowired AnalysisBatchSummaryRepository summaries;
    Member member; Work work; UploadBatch batch;

    @BeforeEach
    void setup() {
        member = Member.register(UUID.randomUUID() + "@local.invalid", "test-only", "01012345678", "합성 작가");
        em.persist(member); work = Work.create(member, "요약 검사", WorkGenre.FANTASY, null); em.persist(work);
        batch = UploadBatch.create(work, member, UploadType.MULTI_EPISODE_MULTI_FILE, UploadSourceType.FILE); em.persist(batch);
    }

    @Test
    @DisplayName("선행 실패 뒤 대기는 숫자로 남기되 진행 중으로 세지 않는다")
    void countsBlockedPendingAfterCompletedPrefix() {
        UUID run = UUID.randomUUID();
        AnalysisJob first = ordered(1, run, 1, 0, null, "SUCCEEDED", "SEALED", true, true);
        AnalysisJob failed = ordered(2, run, 1, 1, first.getId(), "FAILED", "INCOMPLETE", true, false);
        AnalysisJob pending = ordered(3, run, 1, 2, failed.getId(), "PENDING", "PENDING", true, false);
        var result = onlyBatch(); var group = result.episodeJobGroups().getFirst();
        assertThat(result.episodeStatus()).isEqualTo(AnalysisBatchStatus.PARTIALLY_FAILED);
        assertThat(group.totalJobCount()).isEqualTo(3); assertThat(group.succeededJobCount()).isEqualTo(1);
        assertThat(group.failedJobCount()).isEqualTo(1); assertThat(group.pendingJobCount()).isEqualTo(1);
        assertThat(group.currentAnalysisJobIds()).containsExactlyInAnyOrder(first.getId(), failed.getId(), pending.getId());
    }

    @Test
    @DisplayName("다른 generation의 앞선 실패는 현재 대기를 차단하지 않는다")
    void ignoresFailuresFromOtherGenerationForBlocking() {
        UUID run = UUID.randomUUID();
        AnalysisJob old = ordered(1, run, 1, 0, null, "FAILED", "INCOMPLETE", false, false);
        ordered(2, run, 2, 1, old.getId(), "PENDING", "PENDING", false, false);
        assertThat(onlyBatch().episodeStatus()).isEqualTo(AnalysisBatchStatus.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INCOMPLETE", "NULL", "SEALED"})
    @DisplayName("봉인이나 자동 반영이 빠진 성공 회차는 실패로 표시한다")
    void listsIncompleteEpisodeSuccessAsFailed(String journal) {
        AnalysisJob job = ordered(1, UUID.randomUUID(), 1, 0, null, "SUCCEEDED",
                journal.equals("NULL") ? "INCOMPLETE" : journal, true, false);
        em.flush();
        if (journal.equals("NULL")) {
            // 정상 schema가 거절하는 과거 손상 상태를 rollback되는 테스트 트랜잭션에만 주입한다.
            em.createNativeQuery("alter table analysis_jobs drop constraint ck_analysis_jobs_ordered_run").executeUpdate();
            em.createNativeQuery("update analysis_jobs set journal_status=null where id=:id").setParameter("id", job.getId()).executeUpdate();
        }
        var result = onlyBatch(); assertThat(result.episodeStatus()).isEqualTo(AnalysisBatchStatus.FAILED);
        assertThat(result.episodeJobGroups().getFirst().failedJobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("무효화는 원시 성공보다 우선하여 취소로 집계한다")
    void invalidatedOverridesRawSuccess() {
        ordered(1, UUID.randomUUID(), 1, 0, null, "SUCCEEDED", "INVALIDATED", true, true);
        var result = onlyBatch(); assertThat(result.status()).isEqualTo(AnalysisBatchStatus.CANCELED);
        assertThat(result.episodeJobGroups().getFirst().canceledJobCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORLD_CANDIDATES_PUBLISHED", "WORLD_COMPARISONS_FINISHED"})
    @DisplayName("보존된 세계관 checkpoint의 토큰 중단은 그룹 일반 실패에서 제외한다")
    void preservesResumableTokenInterruption(String checkpoint) {
        AnalysisJob job = normal(1); job.updateCheckpointStage(AnalysisJobCheckpointStage.valueOf(checkpoint));
        job.fail(AnalysisFailureCode.AI_TOKEN_QUOTA_EXHAUSTED, "test-only");
        var group = onlyBatch().episodeJobGroups().getFirst();
        assertThat(group.status()).isEqualTo(AnalysisBatchStatus.COMPLETED); assertThat(group.failedJobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 요청 시각에는 UUID 내림차순으로 현재 재시도를 선택한다")
    void breaksTimestampTiesByUuid() {
        AnalysisJob one = normal(1); AnalysisJob two = AnalysisJob.create(work, batch, one.getEpisode(), AnalysisJobType.SETTING_EXTRACTION); em.persist(two);
        LocalDateTime same = LocalDateTime.of(2026, 10, 20, 12, 0); em.flush();
        em.createNativeQuery("update analysis_jobs set created_at=:at where batch_id=:batch")
                .setParameter("at", same).setParameter("batch", batch.getId()).executeUpdate();
        UUID expected = List.of(one.getId(), two.getId()).stream().max(Comparator.comparing(UUID::toString)).orElseThrow();
        assertThat(onlyBatch().episodeJobGroups().getFirst().currentAnalysisJobIds()).containsExactly(expected);
    }

    @Test
    @DisplayName("보존된 복수 대상이 단일 episode보다 우선하고 이동 ID는 중복하지 않는다")
    void usesSnapshotTargetsBeforeEpisodeAndDeduplicatesNavigation() {
        AnalysisJob job = normal(1); Episode second = episode(2); Episode third = episode(3);
        ReflectionTestUtils.setField(job, "targetEpisodes", new LinkedHashSet<>(List.of(second, third)));
        job.succeed("{}", 0, 0);
        var result = onlyBatch(); var group = result.episodeJobGroups().getFirst();
        assertThat(result.episodeStartNo()).isEqualTo(2); assertThat(result.episodeEndNo()).isEqualTo(3);
        assertThat(result.episodeCount()).isEqualTo(2); assertThat(group.totalJobCount()).isEqualTo(2);
        assertThat(group.succeededJobCount()).isEqualTo(2); assertThat(group.currentAnalysisJobIds()).containsExactly(job.getId());
    }

    @Test
    @DisplayName("대상 없는 과거 작업은 같은 목적의 회차가 있으면 현재 집계에서 빠진다")
    void suppressesTargetlessFallbackWhenEpisodeExists() {
        AnalysisJob fallback = AnalysisJob.create(work, batch, null, AnalysisJobType.SETTING_EXTRACTION); em.persist(fallback);
        fallback.fail("test-only"); AnalysisJob current = normal(1);
        assertThat(onlyBatch().episodeJobGroups().getFirst().currentAnalysisJobIds()).containsExactly(current.getId());
    }

    @Test
    @DisplayName("비교 전용 배치와 빈 페이지는 요약 SQL을 실행하지 않는다")
    void excludesComparisonOnlyAndEmptyPages() {
        AnalysisJob comparison = AnalysisJob.create(work, batch, episode(1), AnalysisJobType.WORLD_SETTING_COMPARISON); em.persist(comparison);
        em.flush(); em.clear(); SqlCapture.statements.clear();
        assertThat(service.getAnalysisBatches(member.getId(), work.getId(), 0, 10).content()).isEmpty();
        assertThat(SqlCapture.statements).noneMatch(sql -> sql.contains("with public_jobs"));
        SqlCapture.statements.clear(); assertThat(summaries.readSummaries(work.getId(), List.of())).isEmpty();
        assertThat(SqlCapture.statements).isEmpty();
    }

    @Test
    @DisplayName("다른 회원의 작품은 요약 조회 전에 거절한다")
    void rejectsForeignOwnerBeforeSummaryRead() {
        normal(1); em.flush(); SqlCapture.statements.clear();
        assertThatThrownBy(() -> service.getAnalysisBatches(member.getId() + 1000, work.getId(), 0, 10)).isInstanceOf(AppException.class);
        assertThat(SqlCapture.statements).noneMatch(sql -> sql.contains("with public_jobs"));
    }

    @Test
    @DisplayName("작업 수가 늘어도 JSON이나 분석 엔티티를 읽지 않고 쿼리 수를 유지한다")
    void readsNoHeavyEntitiesOrPerJobQueries() {
        normal(1); normal(2); onlyBatch(); int firstQueryCount = selectCount();
        for (int n = 3; n <= 10; n++) normal(n);
        var result = onlyBatch(); int manyQueryCount = selectCount();
        assertThat(result.episodeJobGroups().getFirst().totalJobCount()).isEqualTo(10);
        assertThat(manyQueryCount).isEqualTo(firstQueryCount);
        assertThat(String.join("\n", SqlCapture.statements)).doesNotContain(
                "automatic_input_state", "run_base_state", "state_journal", "summary_json", "error_message");
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        for (String entity : List.of("analysis.entity.AnalysisJob", "episode.entity.Episode", "upload.entity.UploadFile",
                "character.entity.SettingCandidate", "worldsetting.entity.WorldSettingCandidate")) {
            assertThat(statistics.getEntityStatistics("org.monitoring.catchholebackend.domain." + entity).getLoadCount()).isZero();
        }
    }

    @Test
    @DisplayName("설정집 카드는 회차 그룹 없이 최신 원본과 정규화된 표시 상태를 제공한다")
    void providesBookOnlySummary() {
        UploadFile book = book(); AnalysisJob job = bookJob(book, "SUCCEEDED", "SEALED", false);
        var result = onlyBatch();
        assertThat(result.episodeJobGroups()).isEmpty(); assertThat(result.episodeStatus()).isNull();
        assertThat(result.settingBooks()).hasSize(1); var card = result.settingBooks().getFirst();
        assertThat(card.sourceAnalysisJobId()).isEqualTo(job.getId()); assertThat(card.settingBookId()).isEqualTo(book.getId());
        assertThat(card.revisionId()).isEqualTo(book.getCurrentSettingBookRevision().getId());
        // 회차 목록과 다른 기존 설정집 UI 계약: 자동 반영 latch가 없으면 진행 중이다.
        assertThat(card.status()).isEqualTo(AnalysisBatchSettingBookStatus.IN_PROGRESS);
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,SEALED,true,COMPLETED", "SUCCEEDED,INCOMPLETE,false,INTERRUPTED",
            "FAILED,PENDING,false,INTERRUPTED", "FAILED,INVALIDATED,false,INVALIDATED",
            "CANCELED,PENDING,false,CANCELED", "PENDING,PENDING,false,PENDING", "RUNNING,PENDING,false,IN_PROGRESS"})
    @DisplayName("설정집 완료·중단·무효화 표시의 기존 우선순위를 보존한다")
    void preservesBookDisplayStatus(String rawStatus, String journal, boolean applied, String expected) {
        bookJob(book(), rawStatus, journal, applied);
        var card = onlyBatch().settingBooks().getFirst();
        assertThat(card.status()).isEqualTo(AnalysisBatchSettingBookStatus.valueOf(expected));
    }

    @Test
    @DisplayName("혼합 배치의 이동 ID와 후보 집계는 회차와 설정집 원본별로 분리한다")
    void separatesMixedSourcesAndOriginCandidateCounts() {
        AnalysisJob episode = normal(1); UploadFile book = book();
        AnalysisJob origin = bookJob(book, "SUCCEEDED", "SEALED", true);
        candidate(episode, false, "PENDING_REVIEW", "COMPLETED");
        candidate(origin, false, "CONFIRMED", "COMPLETED");
        candidate(origin, false, "DISMISSED", "COMPLETED");
        candidate(origin, false, "PENDING_REVIEW", "COMPLETED");
        candidate(origin, false, "PENDING_REVIEW", "PENDING");
        candidate(origin, true, "PENDING_REVIEW", "PROCESSING");
        candidate(origin, true, "PENDING_REVIEW", "FAILED");
        var result = onlyBatch(); var card = result.settingBooks().getFirst();
        assertThat(result.jobGroups().getFirst().totalJobCount()).isEqualTo(2);
        assertThat(result.episodeJobGroups().getFirst().currentAnalysisJobIds()).containsExactly(episode.getId());
        assertThat(result.episodeCount()).isEqualTo(1);
        assertThat(result.totalCandidateCount()).isEqualTo(1); assertThat(result.worldSettingTotalCandidateCount()).isZero();
        assertThat(card.sourceAnalysisJobId()).isEqualTo(origin.getId());
        assertThat(card.characterCandidates()).isEqualTo(new org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchCandidateCountsResponse(4, 2, 2, 1, 1));
        assertThat(card.worldCandidates()).isEqualTo(new org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchCandidateCountsResponse(2, 0, 2, 1, 1));
    }

    @Test
    @DisplayName("직접 검토가 없어도 처리 중 후보 수를 잃지 않는다")
    void preservesProcessingOnlyAndZeroCandidateCounts() {
        AnalysisJob origin = bookJob(book(), "SUCCEEDED", "SEALED", true);
        candidate(origin, false, "PENDING_REVIEW", "PROCESSING");
        var card = onlyBatch().settingBooks().getFirst();
        assertThat(card.characterCandidates().directReviewCandidateCount()).isZero();
        assertThat(card.characterCandidates().processingCandidateCount()).isEqualTo(1);
        assertThat(card.worldCandidates().totalCandidateCount()).isZero();
    }

    @Test
    @DisplayName("자동 반영 대기 중인 설정집 후보는 직접 검토 대신 처리 중으로 센다")
    void keepsUnappliedActiveBookCandidatesProcessing() {
        AnalysisJob origin = bookJob(book(), "PENDING", "PENDING", false);
        candidate(origin, false, "PENDING_REVIEW", "COMPLETED");
        candidate(origin, true, "PENDING_REVIEW", "COMPLETED");
        var card = onlyBatch().settingBooks().getFirst();
        assertThat(card.characterCandidates().directReviewCandidateCount()).isZero();
        assertThat(card.characterCandidates().processingCandidateCount()).isEqualTo(1);
        assertThat(card.worldCandidates().directReviewCandidateCount()).isZero();
        assertThat(card.worldCandidates().processingCandidateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("설정집 최신 재시도와 리비전을 선택하고 이전 후보를 합산하지 않는다")
    void selectsLatestBookRetryAcrossRevisions() {
        UploadFile book = book(); AnalysisJob old = bookJob(book, "SUCCEEDED", "SEALED", true);
        candidate(old, false, "PENDING_REVIEW", "COMPLETED");
        SettingBookRevision next = SettingBookRevision.create(book, 2, new StoredTextObject("synthetic/book2", "v2", "e".repeat(64), 100));
        em.persist(next); book.linkCurrentRevision(next);
        AnalysisJob latest = bookJob(book, "FAILED", "INCOMPLETE", false);
        candidate(latest, false, "PENDING_REVIEW", "COMPLETED");
        candidate(latest, false, "PENDING_REVIEW", "COMPLETED");
        em.flush();
        em.createNativeQuery("update analysis_jobs set created_at=:at where id=:id")
                .setParameter("at", LocalDateTime.of(2026, 10, 20, 12, 0)).setParameter("id", old.getId()).executeUpdate();
        em.createNativeQuery("update analysis_jobs set created_at=:at where id=:id")
                .setParameter("at", LocalDateTime.of(2026, 10, 21, 12, 0)).setParameter("id", latest.getId()).executeUpdate();
        var result = onlyBatch(); var card = result.settingBooks().getFirst();
        assertThat(result.settingBooks()).hasSize(1); assertThat(card.sourceAnalysisJobId()).isEqualTo(latest.getId());
        assertThat(card.revisionId()).isEqualTo(next.getId()); assertThat(card.characterCandidates().totalCandidateCount()).isEqualTo(2);
        assertThat(card.historical()).isFalse(); assertThat(card.contentChangedSinceAnalysis()).isFalse();
    }

    @Test
    @DisplayName("원문 변경 후에도 분석 당시 리비전과 과거 여부를 보존한다")
    void preservesHistoricalRevisionAfterSourceEdit() {
        UploadFile book = book(); AnalysisJob origin = bookJob(book, "SUCCEEDED", "SEALED", true);
        UUID sourceRevision = book.getCurrentSettingBookRevision().getId();
        SettingBookRevision next = SettingBookRevision.create(book, 2, new StoredTextObject("synthetic/book2", "v2", "e".repeat(64), 200));
        em.persist(next); book.linkCurrentRevision(next);
        var card = onlyBatch().settingBooks().getFirst();
        assertThat(card.sourceAnalysisJobId()).isEqualTo(origin.getId()); assertThat(card.revisionId()).isEqualTo(sourceRevision);
        assertThat(card.contentChangedSinceAnalysis()).isTrue(); assertThat(card.historical()).isTrue();
    }

    @Test
    @DisplayName("설정집 대상이 있으면 같은 목적의 targetless fallback도 제외한다")
    void suppressesTargetlessFallbackWhenBookExists() {
        em.persist(AnalysisJob.create(work, batch, null, AnalysisJobType.SETTING_EXTRACTION));
        AnalysisJob origin = bookJob(book(), "SUCCEEDED", "SEALED", true);
        var result = onlyBatch(); assertThat(result.episodeJobGroups()).isEmpty();
        assertThat(result.jobGroups().getFirst().currentAnalysisJobIds()).containsExactly(origin.getId());
    }

    @Test
    @DisplayName("선택한 페이지 밖의 설정집이나 후보는 응답하지 않는다")
    void scopesBookCardsToSelectedPage() {
        AnalysisJob first = bookJob(book(), "SUCCEEDED", "SEALED", true); UploadBatch firstBatch = batch;
        batch = UploadBatch.create(work, member, UploadType.SETTING_BOOK, UploadSourceType.FILE); em.persist(batch);
        AnalysisJob second = bookJob(book(), "SUCCEEDED", "SEALED", true);
        em.flush();
        em.createNativeQuery("update analysis_jobs set created_at=:at where id=:id")
                .setParameter("at", LocalDateTime.of(2026, 10, 20, 12, 0)).setParameter("id", first.getId()).executeUpdate();
        em.createNativeQuery("update analysis_jobs set created_at=:at where id=:id")
                .setParameter("at", LocalDateTime.of(2026, 10, 21, 12, 0)).setParameter("id", second.getId()).executeUpdate();
        em.clear(); var page = service.getAnalysisBatches(member.getId(), work.getId(), 1, 1);
        assertThat(page.content()).hasSize(1); assertThat(page.content().getFirst().batchId()).isEqualTo(firstBatch.getId());
        assertThat(page.content().getFirst().settingBooks()).extracting(card -> card.sourceAnalysisJobId()).containsExactly(first.getId());
    }

    private void candidate(AnalysisJob origin, boolean world, String review, String comparison) {
        em.flush();
        String source = origin.isSettingBookSource() ? "SETTING_BOOK" : "EPISODE";
        UUID bookId = origin.isSettingBookSource() ? origin.getSourceSettingBook().getId() : null;
        UUID revisionId = origin.isSettingBookSource() ? origin.getSourceSettingBookRevision().getId() : null;
        UUID episodeId = origin.isSettingBookSource() ? null : origin.getEpisode().getId();
        String sql = world ? """
                insert into world_setting_candidates(id,work_id,analysis_job_id,source_kind,source_setting_book_id,
                    source_setting_book_revision_id,source_episode_id,category,subject_name,setting_name,extracted_value,
                    evidence_spans,review_status,comparison_status,suggested_operation,created_at,updated_at)
                values(:id,:work,:job,:source,:book,:revision,:episode,'LOCATION','합성 장소','특징','합성 값',
                    cast('[]' as jsonb),:review,:comparison,'ADD',current_timestamp,current_timestamp)
                """ : """
                insert into setting_candidates(id,work_id,analysis_job_id,source_kind,source_setting_book_id,
                    source_setting_book_revision_id,episode_id,entity_type,entity_name,match_status,candidate_kind,
                    attribute_name,value_type,review_status,comparison_status,suggested_operation,created_at,updated_at)
                values(:id,:work,:job,:source,:book,:revision,:episode,'CHARACTER','합성 인물','UNRESOLVED','SETTING',
                    '성격','STRING',:review,:comparison,'ADD',current_timestamp,current_timestamp)
                """;
        em.createNativeQuery(sql).setParameter("id", UUID.randomUUID()).setParameter("work", work.getId())
                .setParameter("job", origin.getId()).setParameter("source", source).setParameter("book", bookId)
                .setParameter("revision", revisionId).setParameter("episode", episodeId)
                .setParameter("review", review).setParameter("comparison", comparison).executeUpdate();
    }

    private int selectCount() { return (int) SqlCapture.statements.stream().filter(sql -> sql.startsWith("select") || sql.startsWith("with")).count(); }
    private AnalysisBatchSummaryResponse onlyBatch() {
        em.flush(); em.clear(); SqlCapture.statements.clear();
        em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics().clear();
        return service.getAnalysisBatches(member.getId(), work.getId(), 0, 10).content().getFirst();
    }
    private Episode episode(int n) {
        Episode episode = Episode.create(work, null, n, n + "화", "synthetic/" + n, "v1", "a".repeat(64), 100); em.persist(episode); return episode;
    }
    private AnalysisJob normal(int n) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode(n), AnalysisJobType.SETTING_EXTRACTION); em.persist(job); return job;
    }
    private AnalysisJob ordered(int n, UUID run, long generation, int sequence, UUID predecessor,
            String status, String journal, boolean automatic, boolean applied) {
        AnalysisJob job = AnalysisJob.create(work, batch, episode(n), AnalysisJobType.SETTING_EXTRACTION);
        if (automatic) job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
        job.initializeOrderedRun(run, generation, sequence, predecessor, sequence == 0 ? JsonNodeFactory.instance.objectNode() : null);
        setState(job, status, journal, applied); em.persist(job); return job;
    }
    private UploadFile book() {
        UploadFile book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "합성 설정집.txt", "text/plain", "s3://synthetic/book", 100); em.persist(book);
        SettingBookRevision revision = SettingBookRevision.create(book, 1, new StoredTextObject("synthetic/book", "v1", "b".repeat(64), 100)); em.persist(revision); book.linkCurrentRevision(revision); return book;
    }
    private AnalysisJob bookJob(UploadFile book, String status, String journal, boolean applied) {
        AnalysisJob job = AnalysisJob.createSettingBook(work, book, book.getCurrentSettingBookRevision());
        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode()); setState(job, status, journal, applied); em.persist(job); return job;
    }
    private void setState(AnalysisJob job, String status, String journal, boolean applied) {
        ReflectionTestUtils.setField(job, "status", AnalysisJobStatus.valueOf(status));
        ReflectionTestUtils.setField(job, "journalStatus", AnalysisJournalStatus.valueOf(journal));
        if (journal.equals("SEALED")) {
            ReflectionTestUtils.setField(job, "inputStateHash", "c".repeat(64));
            ReflectionTestUtils.setField(job, "stateJournal", JsonNodeFactory.instance.objectNode().put("outputStateHash", "d".repeat(64)).set("changes", JsonNodeFactory.instance.arrayNode()));
        }
        if (applied) ReflectionTestUtils.setField(job, "automaticAppliedAt", LocalDateTime.of(2026, 10, 20, 12, 0));
    }
}
