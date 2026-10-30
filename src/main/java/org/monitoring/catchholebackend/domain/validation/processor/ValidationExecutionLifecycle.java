package org.monitoring.catchholebackend.domain.validation.processor;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.validation.event.ValidationStagingEvents.ReportInvalidated;
import org.monitoring.catchholebackend.domain.validation.repository.*;
import org.monitoring.catchholebackend.domain.validation.service.ValidationUsageService;
import org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
/** Source/settings owner already holds Work. Deletes every copied payload in that same transaction. */
@Component @RequiredArgsConstructor
public class ValidationExecutionLifecycle {
    private final ValidationJobRepository jobs;
    private final ValidationExecutionRepository results;
    private final ValidationUsageService usage;
    @EventListener @Transactional public void invalidated(ReportInvalidated e) {
        var found=jobs.findByReportId(e.reportId());
        if(found.isPresent()) {var j=jobs.findForUpdate(found.get().getId()).orElseThrow();usage.releaseReservedValidationUsage(j,AiTokenUsageOutcome.USAGE_UNAVAILABLE);j.cancel();}
        results.purge(e.reportId());
    }
    @Transactional public boolean cancelForWorkPurge(UUID work) {
        boolean running=false;for(var existing:jobs.findAllByWorkId(work)) {
            var j=jobs.findForUpdate(existing.getId()).orElseThrow();running|=j.getStatus()==ValidationExecutionStatus.RUNNING||usage.getValidationUsage(j.getId()).stream().anyMatch(u->java.util.Set.of("RESERVED","UNKNOWN").contains(u.get("status").toString()));
            usage.releaseReservedValidationUsage(j,AiTokenUsageOutcome.WORK_PURGE_CANCELED);results.purge(j.getReport().getId());j.cancel();j.getReport().advanceVersion();
        }return running;
    }
}
