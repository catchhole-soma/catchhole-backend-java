package org.monitoring.catchholebackend.domain.analysis.processor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisFailureNotificationMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisFailureNotificationRepository;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;

@Slf4j
public class AnalysisFailureNotificationDispatcher implements AutoCloseable {
    private final AnalysisFailureNotificationRepository repository;
    private final DiscordWebhookClient client;
    private final AnalysisFailureNotificationMapper mapper;
    private final String environment;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analysis-discord-notifications");
        thread.setDaemon(true);
        return thread;
    });
    private Instant nextSendAt = Instant.MIN;

    public AnalysisFailureNotificationDispatcher(AnalysisFailureNotificationRepository repository,
            DiscordWebhookClient client, AnalysisFailureNotificationMapper mapper, String environment) {
        this.repository = repository;
        this.client = client;
        this.mapper = mapper;
        this.environment = environment;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::dispatchNext, 5, 1, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> {
            try { repository.deleteDeliveredHistory(); }
            catch (RuntimeException ex) { log.warn("분석 실패 알림의 발송 완료 이력 정리에 실패했습니다."); }
        }, 1, 24, TimeUnit.HOURS);
    }

    public void dispatchNext() {
        if (Instant.now().isBefore(nextSendAt)) return;
        try {
            var pending = repository.claimNext();
            if (pending.isEmpty()) return;
            var event = pending.get();
            var delivery = client.send(mapper.toMessage(event, environment));
            if (delivery.delivered()) {
                repository.markDelivered(event);
                nextSendAt = Instant.now().plus(delivery.pause());
            } else {
                Duration backoff = Duration.ofSeconds(Math.min(3600, 5L << Math.min(10, event.attempts() - 1)));
                Duration delay = delivery.pause().compareTo(backoff) > 0 ? delivery.pause() : backoff;
                repository.postpone(event, delay);
                // 429·잘못된 웹훅은 다른 대기 알림도 같은 목적지로 보내지 않는다.
                nextSendAt = Instant.now().plus(delay);
                log.warn("분석 실패 알림 발송을 재시도합니다. notificationId={}, attempt={}", event.id(), event.attempts());
            }
        } catch (RuntimeException ex) {
            nextSendAt = Instant.now().plusSeconds(30);
            log.warn("분석 실패 알림 처리에 실패했습니다. 대기 기록은 보존됩니다.");
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
