package org.monitoring.catchholebackend.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.request.PhoneVerificationConfirmRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.PhoneVerificationSendRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.PhoneVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.service.PhoneVerificationService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/auth/phone-verifications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Auth", description = "이메일/비밀번호와 휴대폰 번호 소유 확인 기반 인증 API")
public class PhoneVerificationController {

    private final PhoneVerificationService phoneVerificationService;

    @PostMapping
    @Operation(
            operationId = "requestPhoneVerification",
            summary = "휴대폰 인증번호 발송",
            description = "가입되지 않은 휴대폰 번호로 6자리 인증번호를 발송합니다. "
                    + "재전송은 60초 뒤 가능하며 가장 최근 인증번호만 유효합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인증번호 발송 요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "휴대폰 번호 형식 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 가입된 휴대폰 번호",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "재전송 대기 또는 발송량 제한. Retry-After 헤더를 함께 반환합니다.",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Redis 또는 SMS 발송 서비스 장애",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<PhoneVerificationSendResponse> requestVerification(
            @Valid @RequestBody PhoneVerificationSendRequest request,
            @Parameter(hidden = true) HttpServletRequest servletRequest
    ) {
        PhoneVerificationSendResponse response = phoneVerificationService.start(
                request.phoneNumber(),
                servletRequest.getRemoteAddr()
        );
        return CommonResponse.success("인증번호를 발송했습니다.", response);
    }

    @PostMapping("/{verificationId}/confirm")
    @Operation(
            operationId = "confirmPhoneVerification",
            summary = "휴대폰 인증번호 확인",
            description = "가장 최근에 발송된 인증번호를 확인하고 10분 동안 유효한 1회용 회원가입 토큰을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "휴대폰 인증 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "인증번호 형식·값 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "410",
                    description = "인증 흐름 만료",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "인증번호 5회 오입력",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Redis 장애",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<PhoneVerificationConfirmResponse> confirmVerification(
            @PathVariable String verificationId,
            @Valid @RequestBody PhoneVerificationConfirmRequest request
    ) {
        return CommonResponse.success(
                "휴대폰 인증이 완료되었습니다.",
                phoneVerificationService.confirm(verificationId, request.code())
        );
    }
}
