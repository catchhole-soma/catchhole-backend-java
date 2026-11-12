package org.monitoring.catchholebackend.domain.work.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "work-cover.cleanup", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class WorkCoverCleanupScheduler {
    private final WorkCoverService service;
    @Scheduled(fixedDelayString = "${work-cover.cleanup.fixed-delay-ms:10000}")
    public void retryPendingCleanup() { service.retryPendingCleanup(); }
}
