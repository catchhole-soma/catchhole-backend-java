package org.monitoring.catchholebackend.domain.relationship.processor;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.relationship.service.RelationshipBackfillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "analysis.relationship-backfill",
        name = {"enabled", "scheduling-enabled"},
        havingValue = "true")
public class RelationshipBackfillScheduler {
    private final RelationshipBackfillService coordinator;

    @Scheduled(fixedDelayString = "${analysis.relationship-backfill.fixed-delay-ms:10000}")
    public void bootstrap() {
        coordinator.bootstrap();
    }
}
