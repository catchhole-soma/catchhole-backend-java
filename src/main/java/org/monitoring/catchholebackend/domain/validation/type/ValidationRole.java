package org.monitoring.catchholebackend.domain.validation.type;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
public enum ValidationRole {
    PLANNER("medium"), RELATED_SELECTION("high"), COVERAGE("high"), JUDGE("medium"), TYPO("low"), QUERY_EMBEDDING(null);
    private final String effort;
    ValidationRole(String effort) { this.effort=effort; }
    public String reasoningEffort() { return effort; }
    public AiTokenPurpose purpose() { return AiTokenPurpose.valueOf("VALIDATION_"+name()); }
}
