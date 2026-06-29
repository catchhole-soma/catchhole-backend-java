package org.monitoring.catchholebackend.domain.character.service;

import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;

public interface SettingCandidatePromotionService {

    /**
     * 처음 확정된 설정 후보를 캐릭터 설정 이력과 현재 스냅샷에 반영한다.
     * 같은 후보 재확정은 상위 서비스에서 걸러지므로 여기서는 신규 확정 후보만 처리한다.
     */
    void promote(SettingCandidate candidate);
}
