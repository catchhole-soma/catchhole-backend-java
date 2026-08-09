package org.monitoring.catchholebackend.global.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "공통 에러 응답 정보")
public record ErrorResponse(
        @Schema(description = "에러 코드", example = "REQUEST_VALIDATION_FAILED")
        String code,

        @Schema(description = "HTTP 상태 코드", example = "400")
        int status,

        @Schema(description = "필드별 검증 실패 상세 목록. 검증 실패가 아니면 빈 배열입니다.")
        List<FieldErrorResponse> details,

        @Schema(description = "도메인 충돌의 추가 문맥. 없으면 빈 객체입니다.")
        Map<String, Object> context
) {

    public static ErrorResponse of(String code, int status) {
        return new ErrorResponse(code, status, List.of(), Map.of());
    }

    public static ErrorResponse of(String code, int status, List<FieldErrorResponse> details) {
        return new ErrorResponse(code, status, details, Map.of());
    }

    public static ErrorResponse of(
            String code,
            int status,
            List<FieldErrorResponse> details,
            Map<String, Object> context
    ) {
        return new ErrorResponse(code, status, details, context);
    }
}
