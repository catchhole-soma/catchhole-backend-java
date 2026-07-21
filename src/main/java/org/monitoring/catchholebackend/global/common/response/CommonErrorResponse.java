package org.monitoring.catchholebackend.global.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * {@link CommonResponse CommonResponse&lt;Void&gt;}의 OpenAPI 전용 실패 응답 schema.
 */
@Schema(description = "공통 API 실패 응답 Envelope")
public record CommonErrorResponse(
        @Schema(description = "요청 처리 성공 여부", example = "false")
        boolean success,

        @Schema(description = "에러 메시지", example = "요청 값이 올바르지 않습니다.")
        String message,

        @Schema(description = "실패 응답에서는 null입니다.", nullable = true)
        Void data,

        @Schema(description = "에러 정보")
        ErrorResponse error,

        @Schema(description = "응답 생성 시각", example = "2026-07-21T16:30:00")
        LocalDateTime timestamp
) {
}
