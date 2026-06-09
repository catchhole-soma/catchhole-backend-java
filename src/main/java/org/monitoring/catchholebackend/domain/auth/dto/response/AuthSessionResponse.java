package org.monitoring.catchholebackend.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 세션 처리 응답")
public record AuthSessionResponse(
        @Schema(description = "세션 처리 결과 메시지", example = "로그아웃되었습니다.")
        String message
) {
}
