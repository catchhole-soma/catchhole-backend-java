package org.monitoring.catchholebackend.domain.analysis.event;

import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisRunInvalidationListener {

    private final AiTokenService aiTokenService;

    @EventListener
    public void releaseUnfinishedReservations(AnalysisRunInvalidatedEvent event) {
        aiTokenService.releaseReservedForAnalysisJob(event.analysisJobId(), AiTokenUsageOutcome.USAGE_UNAVAILABLE);
    }
}
