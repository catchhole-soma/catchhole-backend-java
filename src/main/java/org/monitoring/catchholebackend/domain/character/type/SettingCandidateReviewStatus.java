package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum SettingCandidateReviewStatus {

    /**
     * AI Worker가 추출했지만 사용자가 아직 검토하지 않은 설정 후보 상태.
     * SettingCandidate.create()가 기본값으로 설정한다.
     */
    PENDING_REVIEW("검토 대기"),

    /**
     * 사용자가 설정 후보를 기준 설정에 반영하기로 확정한 상태.
     * SettingCandidate.confirm()으로 전환한다.
     */
    CONFIRMED("확정됨"),

    /**
     * 사용자가 설정 후보를 기준 설정에 반영하지 않기로 한 상태.
     * SettingCandidate.dismiss()로 전환한다.
     */
    DISMISSED("무시됨");

    private final String toKorean;
}
