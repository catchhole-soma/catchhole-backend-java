package org.monitoring.catchholebackend.domain.character.type;

public enum CharacterFactComparisonStatus {
    NOT_REQUIRED,
    WAITING_FOR_CHARACTER_MATCH,
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    RECOMPARISON_REQUIRED
}
