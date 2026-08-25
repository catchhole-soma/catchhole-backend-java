package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "추가 AI 사용량 요청 거절")
public record AiTokenExtensionRejectRequest(
        @Schema(description = "운영자 거절 사유", minLength = 1, maxLength = 500)
        @NotBlank
        String reason
) {
}
