package org.monitoring.catchholebackend.domain.member.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenCookieFactory;
import org.monitoring.catchholebackend.domain.member.dto.request.MemberWithdrawalCreateRequest;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberWithdrawalResponse;
import org.monitoring.catchholebackend.domain.member.service.MemberWithdrawalService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/members", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Member", description = "로그인한 회원의 계정 수명주기 API")
@SecurityRequirement(name = "bearerAuth")
public class MemberController {

    private final MemberWithdrawalService withdrawalService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @DeleteMapping("/me")
    @Operation(
            operationId = "withdrawMe",
            summary = "회원 즉시 탈퇴 요청",
            description = "현재 비밀번호와 고정 확인 문구를 검증한 뒤 계정을 즉시 PURGING 상태로 전환합니다. "
                    + "이후 인증을 차단하고 기존 작품 영구 삭제 파이프라인으로 모든 작품을 비동기 파기한 뒤 "
                    + "회원 행을 복구 불가능하게 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "회원 탈퇴 및 영구 파기 요청 접수"),
            @ApiResponse(
                    responseCode = "400",
                    description = "현재 비밀번호 또는 확인 문구 불일치",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public ResponseEntity<CommonResponse<MemberWithdrawalResponse>> withdrawMe(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Valid @RequestBody MemberWithdrawalCreateRequest request
    ) {
        MemberWithdrawalResponse response = withdrawalService.requestWithdrawal(member.memberId(), request);
        ResponseCookie deleteCookie = refreshTokenCookieFactory.delete();
        return ResponseEntity.accepted()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .body(CommonResponse.success("회원 탈퇴 요청이 접수되었습니다.", response));
    }
}
