package org.monitoring.catchholebackend.domain.feedback.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "서비스 의견 안내 노출 여부")
public record FeedbackPromptResponse(
        @Schema(description = "의견 안내 노출 가능 여부. 선점 API에서는 이번 요청만 노출할 수 있는지 반환", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean shouldShow
) {
}
