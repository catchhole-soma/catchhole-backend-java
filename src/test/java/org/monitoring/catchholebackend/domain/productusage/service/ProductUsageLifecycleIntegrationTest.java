package org.monitoring.catchholebackend.domain.productusage.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberWithdrawalDataRepository;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCreateRequest;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.service.WorkService;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test") @Transactional
@DisplayName("제품 사용 집계와 생성·원문 변경·파기의 원자적 연결")
class ProductUsageLifecycleIntegrationTest {
    @Autowired EntityManager em;
    @Autowired ProductUsageService usage;
    @Autowired WorkService works;
    @Autowired WorkPurgeDataRepository workPurge;
    @Autowired MemberWithdrawalDataRepository withdrawal;
    @Autowired AnalysisRunStateService runs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final LocalDateTime start = LocalDateTime.now().minusHours(5);
    Member member;

    @BeforeEach void fixture() {
        jdbc.execute("create table if not exists episode_chunks (id uuid primary key, episode_id uuid not null)");
        usage.initialize(start);
        member = Member.register("usage-lifecycle@example.com", "password", "01087654321", "writer");
        em.persist(member);
    }

    @Test @DisplayName("생성 요청에는 최소 기록만 남기고 수집은 중복 없이 작품 수를 올린다")
    void createsReceiptThenAggregatesOnce() {
        createWork();
        assertThat(count("product_usage_work_receipts")).isEqualTo(1);
        assertThat(total("works_created")).isZero();
        usage.collect(LocalDateTime.now());
        usage.collect(LocalDateTime.now());
        assertThat(total("works_created")).isEqualTo(1);
    }

    @Test @DisplayName("작품 생성이 rollback되면 통계 기록도 함께 사라진다")
    void workCreationRollbackRemovesReceipt() {
        createWork(); em.flush();
        assertThat(count("product_usage_work_receipts")).isEqualTo(1);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThat(count("works")).isZero();
        assertThat(count("product_usage_work_receipts")).isZero();
    }

    @Test @DisplayName("수집 전 작품 파기는 아직 관측하지 않은 완료와 생성을 정산한 뒤 연결 기록을 지운다")
    void settlesUnobservedCompletionBeforeWorkPurge() {
        Work work = createWork();
        completedJob(work, false, true);
        work.startPurging(); em.flush();
        workPurge.purgeWorkData(work.getId()); em.flush(); em.clear();
        assertThat(count("works")).isZero();
        assertThat(count("product_usage_work_receipts")).isZero();
        assertThat(count("product_usage_analysis_receipts")).isZero();
        assertThat(total("works_created")).isEqualTo(1);
        assertThat(total("completed_requests")).isEqualTo(1);
    }

    @Test @DisplayName("60초 안에 원문이 바뀌어도 한때 봉인된 완료는 사라지지 않는다")
    void settlesBeforeJournalInvalidation() {
        Work work = createWork();
        AnalysisJob job = completedJob(work, true, true); em.flush();
        runs.invalidateRunsForEpisodeChangeForUpdate(work.getId(), job.getEpisode().getId(), 1, "test source change");
        em.flush();
        assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
        assertThat(total("completed_requests")).isEqualTo(1);
        usage.collect(LocalDateTime.now());
        assertThat(total("completed_requests")).isEqualTo(1);
    }

    @Test @DisplayName("봉인되지 못한 요청은 원문 변경 때도 완료로 세지 않는다")
    void doesNotCompleteUnsealedRunOnInvalidation() {
        Work work = createWork();
        AnalysisJob job = completedJob(work, true, false); em.flush();
        runs.invalidateRunsForEpisodeChangeForUpdate(work.getId(), job.getEpisode().getId(), 1, "test incomplete change");
        em.flush();
        assertThat(total("completed_requests")).isZero();
    }

    @Test @DisplayName("회원 파기 후 회원 연결 기록은 모두 없어지고 합계만 남는다")
    void withdrawalRemovesIdentityWithoutReducingTotals() {
        Work work = createWork();
        completedJob(work, false, true);
        work.startPurging(); em.flush();
        workPurge.purgeWorkData(work.getId()); em.flush();
        Long memberId = member.getId();
        em.clear();
        member = em.find(Member.class, memberId);
        withdrawal.purgeMemberReferences(member.getId(), LocalDateTime.now());
        em.remove(member); em.flush(); em.clear();
        assertThat(count("members")).isZero();
        assertThat(count("product_usage_members")).isZero();
        assertThat(count("product_usage_member_days")).isZero();
        assertThat(count("product_usage_analysis_receipts")).isZero();
        assertThat(count("product_usage_work_receipts")).isZero();
        assertThat(total("completed_requests")).isEqualTo(1);
        assertThat(total("works_created")).isEqualTo(1);
    }

    private Work createWork() {
        UUID id = works.createWork(member.getId(), new WorkCreateRequest("usage work", WorkGenre.FANTASY, null)).id();
        return em.find(Work.class, id);
    }

    private AnalysisJob completedJob(Work work, boolean ordered, boolean seal) {
        Episode episode = Episode.create(work, null, 1, "test", "test/source", "v1", "a".repeat(64), 100);
        em.persist(episode);
        AnalysisJob job = AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
        job.configureMetricsRequest(UUID.randomUUID(), start.plusMinutes(1), 1);
        if (ordered) {
            job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, JsonNodeFactory.instance.objectNode());
            if (seal) {
                job.prepareOrderedInput("a".repeat(64));
                job.replacePendingJournal(JsonNodeFactory.instance.objectNode().put("outputStateHash", "b".repeat(64))
                        .set("changes", JsonNodeFactory.instance.arrayNode()));
                job.sealJournal();
            }
        }
        job.succeed(null, 0, 0);
        ReflectionTestUtils.setField(job, "completedAt", start.plusHours(1));
        // resultReadyAt은 비동기 tracker가 아직 기록하지 못한 실제 경계를 재현한다.
        em.persist(job);
        return job;
    }

    private long total(String column) {
        em.flush();
        return ((Number) em.createNativeQuery("select coalesce(sum(" + column + "), 0) from product_usage_monthly")
                .getSingleResult()).longValue();
    }
    private long count(String table) {
        return ((Number) em.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue();
    }
}
