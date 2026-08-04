package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;

@Schema(description = "AI provider 요청 전 토큰 예약 요청")
public record AiTokenReserveRequest(
        @Schema(description = "멱등 처리를 위한 AI 요청 식별자", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID requestId,
        @Schema(description = "토큰을 사용할 분석 작업 식별자", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID analysisJobId,
        @Schema(description = "AI 토큰 사용 목적", example = "SETTING_EXTRACTION", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull AiTokenPurpose purpose,
        @Schema(description = "같은 목적 안에서의 호출 순번", example = "1", minimum = "1")
        @Min(1) int attempt,
        @Schema(description = "provider 모델 이름", example = "gpt-5.6-terra", maxLength = 100)
        @NotBlank @Size(max = 100) String modelName,
        @Schema(description = "provider 호출 전 예상하여 예약할 토큰 수", example = "12000", minimum = "1")
        @Min(1) long reservedTokens
) {
}
