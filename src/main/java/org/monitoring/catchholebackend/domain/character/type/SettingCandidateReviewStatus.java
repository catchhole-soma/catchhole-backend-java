package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum SettingCandidateReviewStatus {
    PENDING_REVIEW("검토 대기"),
    CONFIRMED("확정됨"),
    DISMISSED("무시됨");

    private final String toKorean;
}
