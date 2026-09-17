package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("첫 분석 안내 동시 선점 통합")
class AnalysisGuideConcurrencyIntegrationTest {

    @Autowired private AnalysisGuideService guideService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("동시에 노출을 요청해도 정확히 한 요청만 선점한다")
    void grantsOnlyOneConcurrentClaim() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long memberId = transaction.execute(status -> {
            Member member = memberRepository.save(Member.register("guide-race@example.com", "encoded",
                    "01078788888", "작가"));
            return member.getId();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("선점 시작 대기 시간 초과");
                }
                return guideService.claimGuide(memberId).shouldShow();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(guideService.getGuide(memberId).shouldShow()).isFalse();
            assertThat(memberRepository.findById(memberId).orElseThrow().getAnalysisGuideShownAt()).isNotNull();
        } finally {
            transaction.executeWithoutResult(status -> {
                memberRepository.deleteById(memberId);
            });
        }
    }
}
