package org.monitoring.catchholebackend.domain.paymentreview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "현재 회원의 결제 심사 화면 접근 여부")
public record PaymentReviewAccessResponse(
        @Schema(description = "서버의 심사 회원 허용 목록에 포함되고 기능이 활성화되었는지 여부", example = "false")
        boolean allowed
) {
}
