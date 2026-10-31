package org.monitoring.catchholebackend.domain.relationship.service;

import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobHeartbeatResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;

import java.util.Optional;
import java.util.UUID;

public interface RelationshipBackfillService {
    void bootstrap();

    boolean deferInline(AnalysisJob job);

    long publishedGeneration(UUID workId);

    void coreCompleted(AnalysisJob job);

    Optional<WorkerAnalysisJobPayload> claim(WorkerAnalysisJobClaimRequest request);

    WorkerAnalysisJobHeartbeatResponse heartbeat(UUID id, UUID lease);

    void complete(UUID id, UUID lease, WorkerAnalysisJobCompleteRequest request);

    void fail(UUID id, UUID lease, WorkerAnalysisJobFailRequest request);
}
