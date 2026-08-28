package org.monitoring.catchholebackend.domain.feedback.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "서비스 의견 등록 요청")
public record FeedbackCreateRequest(
        @Schema(
                description = "서비스 의견. 앞뒤 공백을 제외하고 35~1,000자",
                minLength = 35,
                maxLength = 1000,
                example = "캐릭터별 변경 이력을 한 화면에서 비교할 수 있으면 검토 시간이 더 줄어들 것 같습니다."
        )
        @NotBlank
        String content,

        @Schema(
                description = "의견을 작성한 화면의 경로. 쿼리와 fragment를 제외한 내부 경로",
                maxLength = 255,
                example = "/dashboard"
        )
        @Size(max = 255)
        String pagePath
) {
}
