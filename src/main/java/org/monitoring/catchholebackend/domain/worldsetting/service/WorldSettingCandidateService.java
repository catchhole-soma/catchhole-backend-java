package org.monitoring.catchholebackend.domain.worldsetting.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;

public interface WorldSettingCandidateService {

    WorldSettingCandidateListResponse getCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            WorldSettingReviewStatus reviewStatus,
            WorldSettingCategory category,
            WorldSettingOperation operation,
            int page,
            int size
    );

    WorldSettingCandidateResponse getCandidate(Long memberId, UUID workId, UUID batchId, UUID candidateId);

    WorldSettingCandidateResponse updateCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            WorldSettingCandidateUpdateRequest request
    );

    WorldSettingCandidateResponse retryComparison(Long memberId, UUID workId, UUID candidateId);

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
}
