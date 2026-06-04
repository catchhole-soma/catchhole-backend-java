package org.monitoring.catchholebackend.global.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "필드 검증 실패 상세 정보")
public record FieldErrorResponse(
        @Schema(description = "검증에 실패한 필드명", example = "email")
        String field,

        @Schema(description = "필드 검증 실패 메시지", example = "이메일 형식이 올바르지 않습니다.")
        String message
) {
}
