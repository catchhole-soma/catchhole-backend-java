package org.monitoring.catchholebackend.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("의견 안내 동시 선점 통합")
class FeedbackPromptConcurrencyIntegrationTest {

    @Autowired private FeedbackService feedbackService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private WorkRepository workRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("동시에 노출을 요청해도 정확히 한 요청만 선점한다")
    void grantsOnlyOneConcurrentClaim() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Long memberId = transaction.execute(status -> {
            Member member = memberRepository.save(Member.register("prompt-race@example.com", "encoded",
                    "01077778888", "작가"));
            Work work = workRepository.save(Work.create(member, "작품", WorkGenre.FANTASY, null));
            for (int number = 1; number <= 3; number++) {
                episodeRepository.save(Episode.create(work, null, number, null,
                        "test/" + UUID.randomUUID(), null, "hash", 100));
            }
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
                return feedbackService.claimFeedbackPrompt(memberId).shouldShow();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(feedbackService.getFeedbackPrompt(memberId).shouldShow()).isFalse();
            assertThat(memberRepository.findById(memberId).orElseThrow().getFeedbackPromptShownAt()).isNotNull();
        } finally {
            transaction.executeWithoutResult(status -> {
                var works = workRepository.findAll().stream().filter(work -> work.isOwnedBy(memberId)).toList();
                var workIds = works.stream().map(Work::getId).toList();
                episodeRepository.deleteAll(episodeRepository.findAll().stream()
                        .filter(episode -> workIds.contains(episode.getWork().getId())).toList());
                episodeRepository.flush();
                workRepository.deleteAll(works);
                workRepository.flush();
                memberRepository.deleteById(memberId);
            });
        }
    }
}
