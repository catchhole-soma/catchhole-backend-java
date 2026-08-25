package org.monitoring.catchholebackend.domain.aitoken.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 처리 대기 중 추가 AI 사용량 요청")
public record AiTokenExtensionPendingResponse(
        @Schema(description = "처리 대기 요청 존재 여부")
        boolean pending,
        @Schema(description = "처리 대기 요청. pending=false이면 null")
        AiTokenExtensionRequestResponse request
) {
}
