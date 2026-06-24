package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CharacterFactReviewStatus {

    /**
     * 캐릭터 개별 설정값이 추출 또는 생성됐지만 아직 사용자가 확인하지 않은 상태.
     * CharacterFact.create()가 기본값으로 설정한다.
     */
    PENDING_REVIEW("검토 대기"),

    /**
     * 사용자가 개별 설정값을 기준 설정 또는 이력으로 확정한 상태.
     * CharacterFact.confirm()으로 전환한다.
     */
    CONFIRMED("확정됨"),

    /**
     * 사용자가 개별 설정값을 기준 설정에 반영하지 않기로 한 상태.
     * CharacterFact.dismiss()로 전환하며, 현재 설정 표시에서도 제외되도록 isCurrent=false가 된다.
     */
    DISMISSED("무시됨");

    private final String toKorean;
}
