package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonFailRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingSubjectResolutionRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonBatchPayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectResolutionPendingResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectResolutionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

public interface WorldSettingWorkerService {

    List<WorkerWorldSettingCandidatePayload> publishWorldSettingCandidates(
            UUID analysisJobId,
            UUID leaseToken,
            WorkerWorldSettingCandidatePublishRequest request
    );

    Optional<WorkerWorldSettingCandidatePayload> claimNextWorldSettingComparison(
            UUID analysisJobId,
            UUID leaseToken
    );

    Optional<WorkerWorldSettingComparisonBatchPayload> claimNextWorldSettingComparisonBatch(
            UUID analysisJobId,
            UUID leaseToken
    );

    WorkerWorldSettingSubjectResolutionPendingResponse
            getPendingWorldSettingSubjectResolutions(
            UUID analysisJobId,
            UUID leaseToken
    );

    WorkerWorldSettingSubjectResolutionResponse resolveWorldSettingSubjects(
            UUID analysisJobId,
            UUID leaseToken,
            WorkerWorldSettingSubjectResolutionRequest request
    );

    WorkerWorldSettingComparisonBatchContextResponse getWorldSettingComparisonBatchContext(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerWorldSettingComparisonBatchContextRequest request
    );

    void completeWorldSettingComparisonBatch(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerWorldSettingComparisonBatchCompleteRequest request
    );

    void failWorldSettingComparisonBatch(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken,
            WorkerWorldSettingComparisonFailRequest request
    );

    void resetStaleWorldSettingSubjectResolution(
            UUID analysisJobId,
            UUID comparisonBatchId,
            UUID leaseToken
    );

    WorkerWorldSettingSubjectPageResponse getWorldSettingSubjects(
            UUID analysisJobId,
            UUID leaseToken,
            WorldSettingCategory category,
            int page,
            int size
    );

    WorkerWorldSettingComparisonContextResponse getWorldSettingComparisonContext(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerWorldSettingComparisonContextRequest request
    );

    void completeWorldSettingComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerWorldSettingComparisonCompleteRequest request
    );

    void failWorldSettingComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerWorldSettingComparisonFailRequest request
    );
}
