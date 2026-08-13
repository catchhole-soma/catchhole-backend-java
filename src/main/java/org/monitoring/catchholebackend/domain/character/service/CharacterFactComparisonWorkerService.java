package org.monitoring.catchholebackend.domain.character.service;

import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonFailRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonCandidatePayload;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonContextResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;

public interface CharacterFactComparisonWorkerService {

    Optional<WorkerCharacterFactComparisonCandidatePayload> claimNextCharacterFactComparison(
            UUID analysisJobId,
            UUID leaseToken
    );

    WorkerCharacterFactComparisonContextResponse getCharacterFactComparisonContext(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken
    );

    void completeCharacterFactComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerCharacterFactComparisonCompleteRequest request
    );

    void failCharacterFactComparison(
            UUID analysisJobId,
            UUID candidateId,
            UUID leaseToken,
            WorkerCharacterFactComparisonFailRequest request
    );

    /** 완료 제안이 현재 관련 snapshot 문맥과 여전히 같은지 confirm 직전에 검증한다. */
    boolean hasCurrentContext(SettingCandidate candidate);
}
