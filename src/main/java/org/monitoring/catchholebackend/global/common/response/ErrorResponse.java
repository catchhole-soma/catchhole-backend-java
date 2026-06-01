package org.monitoring.catchholebackend.global.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공통 에러 응답 정보")
public record ErrorResponse(
        @Schema(description = "에러 코드", example = "REQUEST_VALIDATION_FAILED")
        String code,

        @Schema(description = "HTTP 상태 코드", example = "400")
        int status,

        @Schema(description = "필드별 검증 실패 상세 목록. 검증 실패가 아니면 빈 배열입니다.")
        List<FieldErrorResponse> details
) {

    public static ErrorResponse of(String code, int status) {
        return new ErrorResponse(code, status, List.of());
    }

    public static ErrorResponse of(String code, int status, List<FieldErrorResponse> details) {
        return new ErrorResponse(code, status, details);
    }
}
