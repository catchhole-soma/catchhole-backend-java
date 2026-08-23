package org.monitoring.catchholebackend.domain.work.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "work.purge",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class WorkPurgeScheduler {

    private final WorkPurgeProcessor processor;

    @Scheduled(fixedDelayString = "${work.purge.fixed-delay-ms:10000}")
    public void processPendingRequests() {
        processor.processPendingRequests();
    }

    @Scheduled(cron = "${work.purge.cleanup-cron:0 20 3 * * *}")
    public void deleteExpiredAuditRecords() {
        processor.deleteExpiredAuditRecords();
    }
}
