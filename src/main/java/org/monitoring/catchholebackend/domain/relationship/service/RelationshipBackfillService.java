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
    void bootstrapRelationshipBackfill();

    boolean deferInlineRelationshipAnalysis(AnalysisJob job);

    long getPublishedRelationshipGeneration(UUID workId);

    void enqueueCompletedCoreAnalysis(AnalysisJob job);

    Optional<WorkerAnalysisJobPayload> claimRelationshipJob(WorkerAnalysisJobClaimRequest request);

    WorkerAnalysisJobHeartbeatResponse renewRelationshipJobLease(UUID id, UUID lease);

    void completeRelationshipJob(UUID id, UUID lease, WorkerAnalysisJobCompleteRequest request);

    void failRelationshipJob(UUID id, UUID lease, WorkerAnalysisJobFailRequest request);
}
