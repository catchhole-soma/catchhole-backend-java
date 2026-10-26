package org.monitoring.catchholebackend.domain.worldsetting.service;

import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateSummaryResponse;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateDecisionUpdateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateProgressResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingTokenInterruptedResumeResponse;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;

public interface WorldSettingCandidateService {
    WorldSettingCandidateSummaryResponse getWorldSettingCandidateSummary(
            Long memberId, UUID workId, UUID batchId, UUID sourceAnalysisJobId);


    WorldSettingCandidateProgressResponse getWorldSettingCandidateProgress(Long memberId, UUID workId, UUID batchId);

    WorldSettingCandidateListResponse getCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingCategory category,
            WorldSettingSuggestedOperation operation,
            int page,
            int size
    );

    WorldSettingCandidateListResponse getCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingCategory category,
            WorldSettingSuggestedOperation operation,
            int page,
            int size, UUID sourceAnalysisJobId);

    WorldSettingCandidateResponse getCandidate(Long memberId, UUID workId, UUID batchId, UUID candidateId);

    WorldSettingCandidateResponse getCandidate(Long memberId, UUID workId, UUID batchId, UUID candidateId, UUID sourceAnalysisJobId);

    WorldSettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId);

    WorldSettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId, UUID sourceAnalysisJobId);

    WorldSettingTokenInterruptedResumeResponse resumeTokenInterruptedComparisons(
            Long memberId,
            UUID workId,
            UUID batchId
    );

    WorldSettingTokenInterruptedResumeResponse resumeTokenInterruptedComparisons(
            Long memberId,
            UUID workId,
            UUID batchId, UUID sourceAnalysisJobId);

    WorldSettingCandidateDecisionUpdateResponse updateCandidateDecisions(
            Long memberId,
            UUID workId,
            WorldSettingCandidateDecisionUpdateRequest request
    );

    WorldSettingCandidateDecisionUpdateResponse updateCandidateDecisions(
            Long memberId,
            UUID workId,
            WorldSettingCandidateDecisionUpdateRequest request, UUID sourceAnalysisJobId);

    WorldSettingCandidateConfirmResult confirmCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateConfirmRequest request
    );

    WorldSettingCandidateConfirmResult confirmCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateConfirmRequest request, UUID sourceAnalysisJobId);

    WorldSettingCandidateResponse dismissCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateDismissRequest request
    );

    WorldSettingCandidateResponse dismissCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateDismissRequest request, UUID sourceAnalysisJobId);

    WorldSettingCandidateGroupConfirmResult confirmCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupConfirmRequest request
    );

    WorldSettingCandidateGroupConfirmResult confirmCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupConfirmRequest request, UUID sourceAnalysisJobId);

    WorldSettingCandidateGroupActionResponse dismissCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupDismissRequest request
    );

    WorldSettingCandidateGroupActionResponse dismissCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupDismissRequest request, UUID sourceAnalysisJobId);
}
