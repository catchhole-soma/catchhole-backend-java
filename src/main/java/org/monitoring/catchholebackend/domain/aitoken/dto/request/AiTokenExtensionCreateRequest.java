package org.monitoring.catchholebackend.domain.aitoken.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionContext;

@Schema(description = "추가 AI 사용량 요청")
public record AiTokenExtensionCreateRequest(
        @Schema(
                description = "추가 사용량이 필요한 이유와 서비스 피드백. 앞뒤 공백을 제외하고 35~1,000자",
                minLength = 35,
                maxLength = 1000,
                example = "여러 회차를 분석하며 캐릭터 설정 변화를 확인하고 있습니다. 남은 회차도 이어서 검토할 수 있도록 추가 사용량을 요청드립니다."
        )
        @NotBlank
        String feedback,

        @Schema(description = "사용량 부족 안내가 발생한 화면 컨텍스트")
        @NotNull
        AiTokenExtensionContext context
) {
}
