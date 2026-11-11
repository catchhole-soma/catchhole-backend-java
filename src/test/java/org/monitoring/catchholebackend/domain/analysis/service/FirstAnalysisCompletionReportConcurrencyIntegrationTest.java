package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("첫 분석 완료 보고 동시 선점 통합")
class FirstAnalysisCompletionReportConcurrencyIntegrationTest {

    @Autowired private FirstAnalysisCompletionReportService reports;
    @Autowired private MemberRepository memberRepository;
    @Autowired private WorkRepository works;
    @Autowired private AnalysisJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("여러 탭에서 동시에 보고를 선점해도 정확히 한 요청만 이벤트를 보낸다")
    void grantsOnlyOneConcurrentClaim() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long memberId = transaction.execute(status -> memberRepository.save(Member.register(
                "first-done-race@example.com", "encoded", "01078798888", "작가")).getId());
        var workId = transaction.execute(status -> works.save(Work.create(
                memberRepository.getReferenceById(memberId), "동시 보고", WorkGenre.FANTASY, null)).getId());
        transaction.executeWithoutResult(status -> {
            var jobId = jobs.saveAndFlush(AnalysisJob.create(
                    works.getReferenceById(workId), null, null, AnalysisJobType.SETTING_EXTRACTION)).getId();
            jdbc.update("update analysis_jobs set status = 'SUCCEEDED', completed_at = ? where id = ?", LocalDateTime.now(), jobId);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("선점 시작 대기 시간 초과");
                }
                return reports.claimFirstAnalysisCompletionReport(memberId).shouldReport();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(reports.getFirstAnalysisCompletionReport(memberId).shouldReport()).isFalse();
            assertThat(memberRepository.findById(memberId).orElseThrow().getFirstAnalysisCompletionReportedAt()).isNotNull();
        } finally {
            transaction.executeWithoutResult(status -> {
                jdbc.update("delete from analysis_jobs where work_id = ?", workId);
                works.deleteById(workId);
                memberRepository.deleteById(memberId);
            });
        }
    }
}
