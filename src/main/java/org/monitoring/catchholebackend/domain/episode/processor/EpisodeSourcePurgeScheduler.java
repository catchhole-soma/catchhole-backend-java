package org.monitoring.catchholebackend.domain.episode.processor;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "episode.source-purge",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class EpisodeSourcePurgeScheduler {

    private final EpisodeSourcePurgeProcessor processor;

    @Scheduled(fixedDelayString = "${episode.source-purge.fixed-delay-ms:10000}")
    public void processPendingRequests() {
        processor.processPendingRequests();
    }
}
