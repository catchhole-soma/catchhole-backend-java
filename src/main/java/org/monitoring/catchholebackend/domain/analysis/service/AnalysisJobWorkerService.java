package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;

public interface AnalysisJobWorkerService {

    Optional<WorkerAnalysisJobPayload> claimAnalysisJob(WorkerAnalysisJobClaimRequest request);

    void updateProgress(UUID analysisJobId, WorkerAnalysisJobProgressRequest request);

    void completeAnalysisJob(UUID analysisJobId, WorkerAnalysisJobCompleteRequest request);

    void failAnalysisJob(UUID analysisJobId, WorkerAnalysisJobFailRequest request);
}
