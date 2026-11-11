package org.monitoring.catchholebackend.domain.retrieval.processor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.retrieval.mapper.RetrievalIndexLagAlertMapper;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagAlertStateStore.State;
import org.monitoring.catchholebackend.domain.retrieval.repository.RetrievalIndexLagRepository;
import org.monitoring.catchholebackend.domain.retrieval.type.RetrievalIndexLagNotice;
import org.monitoring.catchholebackend.global.config.retrievalalert.RetrievalIndexLagAlertProperties;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 색인돼야 하는데 기준 시간보다 오래 색인되지 않은 회차를 주기적으로 세어 Discord로 알린다(#422).
 * 0→N, 늘었고 반복 간격 지남, 24시간 지남, N→0일 때만 보내고, 보낸 경우에만 마지막 알림을 기록한다.
 * 어떤 오류도 스케줄러 밖으로 던지지 않는다.
 */
@Slf4j
public class RetrievalIndexLagAlertScheduler implements AutoCloseable {
    static final Duration REMINDER_INTERVAL = Duration.ofHours(24);
    static final int TOP_WORKS = 3;

    private final RetrievalIndexLagRepository repository;
    private final RetrievalIndexLagAlertStateStore stateStore;
    private final RetrievalIndexLagAlertMapper mapper;
    /** 웹훅이 없거나 형식이 틀리면 null이며 이때 아무것도 조회하거나 보내지 않는다. */
    private final DiscordWebhookClient client;
    private final String environment;
    private final String embeddingVersion;
    private final RetrievalIndexLagAlertProperties properties;
    private final Clock clock;
    private Instant nextSendAt = Instant.MIN;

    public RetrievalIndexLagAlertScheduler(RetrievalIndexLagRepository repository,
            RetrievalIndexLagAlertStateStore stateStore, RetrievalIndexLagAlertMapper mapper,
            DiscordWebhookClient client, String environment, String embeddingVersion,
            RetrievalIndexLagAlertProperties properties, Clock clock) {
        this.repository = repository;
        this.stateStore = stateStore;
        this.mapper = mapper;
        this.client = client;
        this.environment = environment;
        this.embeddingVersion = embeddingVersion;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${retrieval.index-lag-alert.fixed-delay-ms:600000}")
    public void checkRetrievalIndexLag() {
        if (client == null) return;
        try {
            Instant now = clock.instant();
            // 429·잘못된 웹훅이 요구한 대기 동안은 같은 목적지로 다시 보내지 않는다.
            if (now.isBefore(nextSendAt)) return;
            LocalDateTime localNow = LocalDateTime.ofInstant(now, clock.getZone());
            var lag = repository.read(localNow.minus(properties.threshold()), embeddingVersion, TOP_WORKS);
            State last = stateStore.read();
            RetrievalIndexLagNotice notice = decide(last, lag.episodes(), now, properties.repeatInterval());
            if (notice == null) return;
            var delivery = client.send(mapper.toMessage(notice, lag, last.notifiedCount(), localNow,
                    properties.threshold(), environment));
            nextSendAt = now.plus(delivery.pause());
            if (!delivery.delivered()) {
                // 기록하지 않으므로 다음 확인에서 다시 판단해 보낸다.
                log.warn("검색 색인 지연 알림 발송에 실패했습니다. 다음 확인에서 다시 보냅니다. notice={}", notice);
                return;
            }
            stateStore.write(new State(lag.episodes(), now));
            log.info("검색 색인 지연 알림을 보냈습니다. notice={}, episodes={}, works={}", notice, lag.episodes(), lag.works());
        } catch (RuntimeException ex) {
            log.warn("검색 색인 지연 확인에 실패했습니다. 다음 주기에 다시 확인합니다.");
        }
    }

    /** 보낼 알림 종류, 보내지 않으면 null. */
    static RetrievalIndexLagNotice decide(State last, long lagging, Instant now, Duration repeatInterval) {
        if (lagging == 0) return last.notifiedCount() > 0 ? RetrievalIndexLagNotice.RESOLVED : null;
        if (last.notifiedCount() == 0) return RetrievalIndexLagNotice.STARTED;
        Duration since = Duration.between(last.notifiedAt(), now);
        if (lagging > last.notifiedCount() && since.compareTo(repeatInterval) >= 0) return RetrievalIndexLagNotice.GREW;
        if (since.compareTo(REMINDER_INTERVAL) >= 0) return RetrievalIndexLagNotice.REMINDER;
        return null;
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }
}
