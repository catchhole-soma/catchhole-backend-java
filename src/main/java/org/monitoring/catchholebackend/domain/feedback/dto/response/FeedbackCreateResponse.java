package org.monitoring.catchholebackend.domain.feedback.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenFeedbackRewardOutcome;

@Schema(description = "서비스 의견 등록 결과")
public record FeedbackCreateResponse(
        @Schema(description = "저장된 의견 ID")
        UUID id,
        @Schema(description = "추가 사용량 보상 요청 처리 결과")
        AiTokenFeedbackRewardOutcome rewardRequestOutcome,
        @Schema(description = "연결된 보상 요청 ID. 다른 처리 대기 요청 때문에 생성이 보류되면 null")
        UUID rewardRequestId,
        @Schema(description = "연결된 보상 요청 상태. 요청이 아직 없으면 null")
        AiTokenExtensionStatus rewardRequestStatus,
        @Schema(description = "서버 의견 접수 시각")
        LocalDateTime submittedAt
) {
}
