package org.monitoring.catchholebackend.domain.member.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "member.withdrawal",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MemberWithdrawalScheduler {

    private final MemberWithdrawalProcessor processor;

    @Scheduled(fixedDelayString = "${member.withdrawal.fixed-delay-ms:10000}")
    public void processPendingRequests() {
        processor.processPendingRequests();
    }

    @Scheduled(cron = "${member.withdrawal.cleanup-cron:0 30 3 * * *}")
    public void deleteExpiredAuditRecords() {
        processor.deleteExpiredAuditRecords();
    }
}
