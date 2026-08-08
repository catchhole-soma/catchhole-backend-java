package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonFailRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse;
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
