package org.monitoring.catchholebackend.domain.character.service;

import java.util.List;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactConfirmApplicationMode;

public interface SettingCandidatePromotionService {

    /**
     * 처음 확정된 설정 후보를 캐릭터 설정 이력과 현재 스냅샷에 반영한다.
     * 같은 후보 재확정은 상위 서비스에서 걸러지므로 여기서는 신규 확정 후보만 처리한다.
     */
    void promote(SettingCandidate candidate, CharacterFactConfirmApplicationMode applicationMode);

    /**
     * 기존 캐릭터의 같은 이름 후보 묶음을 순서대로 반영하되 캐릭터별 snapshot version은 한 번만 증가시킨다.
     */
    void promoteGroup(List<SettingCandidateGroupPromotion> promotions);

    /** Apply confirmed representative selections in validated journal order, including semantic duplicates. */
    void finalizeNarrativeTimes(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job);

    /** Replay a final character group's choices together when their original jobs interleave. */
    void finalizeNarrativeTimes(List<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob> jobs);

    /** 고정 원문 검증을 마친 설정집 동일명 그룹만 현재 실행에서 별도 생성한다. */
    void promoteAutomaticGroup(List<SettingCandidateGroupPromotion> promotions,
            org.monitoring.catchholebackend.domain.character.processor.SettingBookIdentityValidator.ValidatedIdentities identities);

    /** 실제 쓰기와 같은 규칙으로 자동 반영 후보를 선행 채택 결과 위에서 검증한다. */
    void validateAutomaticPromotion(SettingCandidate candidate, List<SettingCandidate> earlierAccepted);

    /**
     * 아직 존재하지 않는 같은 이름의 캐릭터를 한 번만 만들고 그룹의 모든 후보를 순서대로 반영한다.
     */
    void promoteNewCharacterGroup(List<SettingCandidateGroupPromotion> promotions);

    /** 같은 검토 그룹에서 승인한 중복 제외의 관측 시점도 새 캐릭터에 연결한다. */
    void promoteNewCharacterGroup(List<SettingCandidateGroupPromotion> promotions,
            List<SettingCandidate> approvedExclusions);
}
