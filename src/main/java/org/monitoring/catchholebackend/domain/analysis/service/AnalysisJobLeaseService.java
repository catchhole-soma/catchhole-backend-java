package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

public interface AnalysisJobLeaseService {

    /** Canonical callbacks lock Work before Job and character state, matching validation admission. */
    void lockWorkForUpdate(UUID analysisJobId);

    AnalysisJob getRunningAnalysisJobForUpdate(UUID analysisJobId, UUID leaseToken);
}
