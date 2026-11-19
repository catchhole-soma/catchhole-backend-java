package org.monitoring.catchholebackend.domain.paymentreview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.paymentreview.dto.response.PaymentReviewAccessResponse;
import org.monitoring.catchholebackend.domain.paymentreview.dto.response.PaymentReviewPageResponse;
import org.monitoring.catchholebackend.domain.paymentreview.mapper.PaymentReviewMapper;
import org.monitoring.catchholebackend.domain.paymentreview.service.PaymentReviewService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/payment-reviews", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "PaymentReview", description = "지정된 회원만 사용하는 PG 심사 화면 조회. 결제 승인·지급·환불은 수행하지 않습니다.")
@SecurityRequirement(name = "bearerAuth")
public class PaymentReviewController {
    private final PaymentReviewService service;
    private final PaymentReviewMapper mapper;

    @GetMapping("/access")
    @Operation(operationId = "getPaymentReviewAccess", summary = "결제 심사 접근 여부 조회",
            description = "인증된 현재 회원 번호와 서버 설정으로 판정합니다. 일반 회원과 비활성 기능은 allowed=false이며 관리자도 자동 허용하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "접근 여부 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public ResponseEntity<CommonResponse<PaymentReviewAccessResponse>> access(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CommonResponse.success(mapper.toAccessResponse(service.isAllowed(member))));
    }

    @GetMapping("/page")
    @Operation(operationId = "getPaymentReviewPage", summary = "결제 심사 화면 자료 조회",
            description = "매 요청마다 현재 회원의 접근 권한을 확인한 뒤 결제창 설정과 법률 문서를 반환합니다. 결제창을 열기 직전에도 재조회합니다. 회원 번호를 요청으로 받지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "화면 자료 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "허용되지 않은 회원 또는 비활성 기능", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public ResponseEntity<CommonResponse<PaymentReviewPageResponse>> page(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CommonResponse.success(service.getPage(member)));
    }
}
