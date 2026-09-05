package org.monitoring.catchholebackend.domain.character.type;

/** Worker 호출 한 번에 원자적으로 비교하는 캐릭터 Fact 후보 묶음 상태다. */
public enum CharacterFactComparisonBatchStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}
