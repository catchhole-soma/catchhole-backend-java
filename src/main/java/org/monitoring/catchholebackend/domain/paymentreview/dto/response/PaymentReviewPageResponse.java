package org.monitoring.catchholebackend.domain.paymentreview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "허용된 심사 회원에게만 제공하는 결제창 설정과 구매·법률 문서. 결제 승인이나 사용량 지급 응답이 아닙니다.")
public record PaymentReviewPageResponse(
        @Schema(description = "PortOne 상점 ID. 비밀 API 키가 아닙니다.", example = "store-example", requiredMode = Schema.RequiredMode.REQUIRED)
        String storeId,
        @Schema(description = "운영자가 TEST 모드임을 확인한 이니시스 채널 키", example = "channel-key-example", requiredMode = Schema.RequiredMode.REQUIRED)
        String channelKey,
        @Schema(description = "결제창에 필요한 설정이 존재하는지 여부. 심사 통과나 판매 개시를 의미하지 않습니다.", example = "false")
        boolean ready,
        @Schema(description = "심사 상품의 구매조건 Markdown 원문", requiredMode = Schema.RequiredMode.REQUIRED)
        String purchaseConditions,
        @Schema(description = "심사 상품에 적용할 이용약관 Markdown 원문. 기존 회원 가입 동의 문서를 대체하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        String terms,
        @Schema(description = "심사 상품에 적용할 개인정보처리방침 Markdown 원문", requiredMode = Schema.RequiredMode.REQUIRED)
        String privacy
) {
}
