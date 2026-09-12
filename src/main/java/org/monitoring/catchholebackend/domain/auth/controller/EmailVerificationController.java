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
import org.monitoring.catchholebackend.domain.auth.dto.request.EmailVerificationConfirmRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.EmailVerificationSendRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.EmailVerificationSendResponse;
import org.monitoring.catchholebackend.domain.auth.service.EmailVerificationService;
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
@RequestMapping(value = "/api/v1/auth/email-verifications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Auth", description = "회원가입 인증과 이메일/비밀번호 로그인 API")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping
    @Operation(
            operationId = "requestEmailVerification",
            summary = "이메일 인증번호 발송",
            description = "가입되지 않은 이메일로 6자리 인증번호를 발송합니다. 기본 유효시간은 5분이며 "
                    + "기본 재전송 대기는 60초입니다. 실제 시간은 응답 값을 기준으로 하며 가장 최근 인증번호만 유효합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인증번호 발송 요청 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "이메일 형식 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 가입된 이메일 또는 현재 비활성화된 인증 방식",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "재전송 대기 또는 발송량 제한. Retry-After 헤더를 함께 반환합니다.",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Redis 또는 메일 발송 서비스 장애",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<EmailVerificationSendResponse> requestEmailVerification(
            @Valid @RequestBody EmailVerificationSendRequest request,
            @Parameter(hidden = true) HttpServletRequest servletRequest
    ) {
        EmailVerificationSendResponse response = emailVerificationService.sendEmailVerificationCode(
                request.email(),
                servletRequest.getRemoteAddr()
        );
        return CommonResponse.success("인증 메일 발송을 요청했습니다.", response);
    }

    @PostMapping("/{verificationId}/confirm")
    @Operation(
            operationId = "confirmEmailVerification",
            summary = "이메일 인증번호 확인",
            description = "가장 최근 인증번호를 확인해 1회용 회원가입 토큰을 발급합니다. 기본 유효시간은 10분이며 "
                    + "실제 남은 시간은 응답 값을 사용합니다. 재전송하면 이전 인증 흐름과 가입 토큰이 폐기됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이메일 인증 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "인증번호 형식·값 오류",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "현재 비활성화된 인증 방식",
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
    public CommonResponse<EmailVerificationConfirmResponse> confirmEmailVerification(
            @PathVariable String verificationId,
            @Valid @RequestBody EmailVerificationConfirmRequest request
    ) {
        return CommonResponse.success(
                "이메일 인증이 완료되었습니다.",
                emailVerificationService.confirmEmailVerificationCode(verificationId, request.code())
        );
    }
}
