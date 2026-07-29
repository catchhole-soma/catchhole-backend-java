package org.monitoring.catchholebackend.domain.character.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateCharacterMatchRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;

public interface SettingCandidateService {

    /**
     * 작품 소유권과 업로드 묶음 소속을 확인한 뒤 해당 묶음의 설정 후보를 페이지 조회한다.
     * 집계와 회차 범위는 필터와 무관한 묶음 전체를 기준으로 하고, 후보 목록에만 검토·연결 상태 필터를 적용한다.
     */
    SettingCandidateListResponse getSettingCandidates(
            Long memberId,
            UUID workId,
            UUID batchId,
            SettingCandidateReviewStatus reviewStatus,
            SettingCandidateMatchStatus matchStatus,
            int page,
            int size
    );

    /**
     * 작품 소유권과 업로드 묶음·설정 후보 소속을 확인한 뒤 단건 후보 정보를 조회한다.
     */
    SettingCandidateResponse getSettingCandidate(Long memberId, UUID workId, UUID batchId, UUID candidateId);

    /**
     * 작품 소유권과 설정 후보 소속을 확인한 뒤 사용자가 검토할 후보 내용을 수정한다.
     * 후보 편집은 PENDING_REVIEW 상태에서만 허용하며, 확정/무시와 CharacterFact 반영은 처리하지 않는다.
     */
    SettingCandidateResponse updateSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateUpdateRequest request
    );

    /**
     * 작품 소유권과 설정 후보 소속을 확인한 뒤 후보의 캐릭터 연결 상태를 해소한다.
     * 후보는 PENDING_REVIEW 상태여야 하며, 검토 상태는 변경하지 않는다.
     */
    SettingCandidateResponse updateSettingCandidateCharacterMatch(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateCharacterMatchRequest request
    );

    /**
     * 작품 소유권과 설정 후보 소속을 확인한 뒤 후보를 확정 상태로 전환한다.
     * 처음 확정되는 후보는 CharacterFact와 WorkCharacter 현재 스냅샷에 반영한다.
     * 이미 확정된 후보는 성공으로 처리하되 중복 반영하지 않고, 무시된 후보는 상태 충돌로 거절한다.
     */
    SettingCandidateReviewStatusResponse confirmSettingCandidate(Long memberId, UUID workId, UUID candidateId);

    /**
     * 작품 소유권과 설정 후보 소속을 확인한 뒤 후보를 무시 상태로 전환한다.
     * 이미 무시된 후보는 성공으로 처리하고, 확정된 후보는 상태 충돌로 거절한다.
     * 무시 처리에서는 확정 데이터 반영을 수행하지 않는다.
     */
    SettingCandidateReviewStatusResponse dismissSettingCandidate(Long memberId, UUID workId, UUID candidateId);
}
