package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CharacterReviewStatus {

    /**
     * AI 추출 또는 사용자 입력으로 만들어졌지만 아직 기준 설정으로 검토되지 않은 상태.
     * WorkCharacter.create()가 기본값으로 설정한다.
     * TODO: WorkCharacter 자체에 PENDING_REVIEW가 필요한지, 후보/Fact 검토 상태만으로 충분한지 후속 결정한다.
     */
    PENDING_REVIEW("검토 대기"),

    /**
     * 사용자가 캐릭터 대표 설정을 기준 설정으로 확정한 상태.
     * WorkCharacter.confirm()으로 전환한다.
     */
    CONFIRMED("확정됨"),

    /**
     * 사용자가 캐릭터 대표 설정을 기준 설정으로 쓰지 않기로 한 상태.
     * WorkCharacter.dismiss()로 전환한다.
     */
    DISMISSED("무시됨");

    private final String toKorean;
}
