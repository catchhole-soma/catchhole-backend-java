package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenFeedbackRewardOutcome;

public record AiTokenFeedbackRewardResult(
        AiTokenFeedbackRewardOutcome outcome,
        UUID requestId,
        AiTokenExtensionStatus requestStatus
) {
}
