package org.monitoring.catchholebackend.domain.character.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum CharacterReviewStatus {
    //TODO: Pending 상태 불필요 수정 필요함
    PENDING_REVIEW("검토 대기"),
    CONFIRMED("확정됨"),
    DISMISSED("무시됨");

    private final String toKorean;
}
