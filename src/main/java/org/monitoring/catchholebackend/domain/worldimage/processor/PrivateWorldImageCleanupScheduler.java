package org.monitoring.catchholebackend.domain.worldimage.processor;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.worldimage.service.PrivateWorldImageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "world-image.cleanup", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class PrivateWorldImageCleanupScheduler {
    private final PrivateWorldImageService service;

    @Scheduled(fixedDelayString = "${world-image.cleanup.fixed-delay-ms:10000}")
    public void retryPendingCleanup() { service.retryPendingCleanup(); }
}
