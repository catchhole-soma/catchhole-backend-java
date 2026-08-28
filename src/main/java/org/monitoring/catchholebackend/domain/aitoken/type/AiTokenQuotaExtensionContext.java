package org.monitoring.catchholebackend.domain.aitoken.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiTokenQuotaExtensionContext {
    REQUEST_BLOCKED(AiTokenExtensionContext.REQUEST_BLOCKED),
    ANALYSIS_FAILED(AiTokenExtensionContext.ANALYSIS_FAILED),
    ANALYSIS_INTERRUPTED(AiTokenExtensionContext.ANALYSIS_INTERRUPTED);

    private final AiTokenExtensionContext extensionContext;
}
