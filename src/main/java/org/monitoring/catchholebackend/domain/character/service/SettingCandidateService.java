package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.request.SettingCandidateUpdateRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;

public interface SettingCandidateService {

    /**
     * 작품 소유권을 확인한 뒤 설정 후보 목록을 최신 생성순으로 조회한다.
     * reviewStatus와 entityName이 전달되면 해당 조건으로 후보 목록을 필터링한다.
     */
    List<SettingCandidateResponse> getSettingCandidates(
            Long memberId,
            UUID workId,
            SettingCandidateReviewStatus reviewStatus,
            String entityName
    );

    /**
     * 작품 소유권과 설정 후보 소속을 확인한 뒤 단건 후보 정보를 조회한다.
     */
    SettingCandidateResponse getSettingCandidate(Long memberId, UUID workId, UUID candidateId);

    /**
     * 작품 소유권과 설정 후보 소속을 확인한 뒤 사용자가 검토할 후보 내용을 수정한다.
     * 확정/무시된 후보는 CharacterFact 반영 정책과 충돌할 수 있으므로 PENDING_REVIEW 상태만 수정한다.
     */
    SettingCandidateResponse updateSettingCandidate(
            Long memberId,
            UUID workId,
            UUID candidateId,
            SettingCandidateUpdateRequest request
    );
}
