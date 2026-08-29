package org.monitoring.catchholebackend.domain.worldsetting.service;

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
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingTokenInterruptedResumeResponse;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;

public interface WorldSettingCandidateService {

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

    WorldSettingCandidateResponse getCandidate(Long memberId, UUID workId, UUID batchId, UUID candidateId);

    WorldSettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId);

    WorldSettingTokenInterruptedResumeResponse resumeTokenInterruptedComparisons(
            Long memberId,
            UUID workId,
            UUID batchId
    );

    WorldSettingCandidateDecisionUpdateResponse updateCandidateDecisions(
            Long memberId,
            UUID workId,
            WorldSettingCandidateDecisionUpdateRequest request
    );

    WorldSettingCandidateConfirmResult confirmCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateConfirmRequest request
    );

    WorldSettingCandidateResponse dismissCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateDismissRequest request
    );

    WorldSettingCandidateGroupConfirmResult confirmCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupConfirmRequest request
    );

    WorldSettingCandidateGroupActionResponse dismissCandidateGroup(
            Long memberId,
            UUID workId,
            WorldSettingCandidateGroupDismissRequest request
    );
}
