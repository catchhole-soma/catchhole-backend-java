package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

public interface AnalysisJobLeaseService {

    AnalysisJob getRunningAnalysisJobForUpdate(UUID analysisJobId, UUID leaseToken);
}
