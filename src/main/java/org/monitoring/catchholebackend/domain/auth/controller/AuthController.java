package org.monitoring.catchholebackend.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthLoginRequest;
import org.monitoring.catchholebackend.domain.auth.dto.request.AuthSignupRequest;
import org.monitoring.catchholebackend.domain.auth.dto.response.AuthSessionResponse;
import org.monitoring.catchholebackend.domain.auth.dto.response.AuthTokenResponse;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.auth.service.AuthService;
import org.monitoring.catchholebackend.domain.auth.service.AuthTokenIssueResult;
import org.monitoring.catchholebackend.domain.auth.token.RefreshTokenCookieFactory;
import org.monitoring.catchholebackend.domain.member.dto.response.MemberResponse;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Auth", description = "이메일/비밀번호 기반 회원가입, 로그인, JWT 재발급, 로그아웃 API")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    @PostMapping("/signup")
    @Operation(
            operationId = "signup",
            summary = "회원가입",
            description = "이메일, 비밀번호, 표시 이름과 휴대폰 인증 완료 토큰으로 신규 회원을 생성합니다. "
                    + "이메일과 휴대폰 번호는 각각 중복 가입을 허용하지 않습니다. "
                    + "가입 완료 후 액세스 토큰은 응답 body로, 리프레시 토큰은 HttpOnly 쿠키로 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원가입 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 검증 실패 또는 유효하지 않은 휴대폰 인증 토큰",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이메일 또는 휴대폰 번호 중복",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Redis 휴대폰 인증 저장소 장애",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public ResponseEntity<CommonResponse<AuthTokenResponse>> signup(
            @Valid @RequestBody AuthSignupRequest request
    ) {
        AuthTokenIssueResult result = authService.signup(request);
        return tokenResponse(result, "회원가입이 완료되었습니다.");
    }

    @PostMapping("/login")
    @Operation(
            operationId = "login",
            summary = "로그인",
            description = "이메일과 비밀번호를 검증한 뒤 액세스 토큰은 응답 body로 반환하고, "
                    + "리프레시 토큰은 HttpOnly 쿠키로 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "이메일 또는 비밀번호 불일치",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public ResponseEntity<CommonResponse<AuthTokenResponse>> login(
            @Valid @RequestBody AuthLoginRequest request
    ) {
        AuthTokenIssueResult result = authService.login(request);
        return tokenResponse(result);
    }

    @PostMapping("/refresh")
    @Operation(
            operationId = "refresh",
            summary = "액세스 토큰 재발급",
            description = "HttpOnly 쿠키의 리프레시 토큰을 검증해 새 액세스 토큰을 발급합니다. "
                    + "재발급 시 기존 리프레시 토큰은 폐기하고 새 리프레시 토큰 쿠키로 회전합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 재발급 성공"),
            @ApiResponse(
                    responseCode = "401",
                    description = "리프레시 토큰 없음, 만료, 폐기 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public ResponseEntity<CommonResponse<AuthTokenResponse>> refresh(
            @Parameter(
                    name = RefreshTokenCookieFactory.COOKIE_NAME,
                    description = "로그인 또는 재발급 시 발급된 리프레시 토큰 HttpOnly 쿠키",
                    in = ParameterIn.COOKIE
            )
            @CookieValue(value = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken
    ) {
        AuthTokenIssueResult result = authService.refresh(refreshToken);
        return tokenResponse(result);
    }

    @PostMapping("/logout")
    @Operation(
            operationId = "logout",
            summary = "로그아웃",
            description = "현재 리프레시 토큰을 폐기하고 브라우저의 리프레시 토큰 쿠키를 삭제합니다. "
                    + "쿠키가 없거나 이미 폐기된 경우에도 클라이언트 쿠키 삭제 응답은 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그아웃 처리 성공")
    })
    public ResponseEntity<CommonResponse<AuthSessionResponse>> logout(
            @Parameter(
                    name = RefreshTokenCookieFactory.COOKIE_NAME,
                    description = "폐기할 리프레시 토큰 HttpOnly 쿠키",
                    in = ParameterIn.COOKIE
            )
            @CookieValue(value = RefreshTokenCookieFactory.COOKIE_NAME, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);
        ResponseCookie deleteCookie = refreshTokenCookieFactory.delete();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .body(CommonResponse.success(new AuthSessionResponse("로그아웃되었습니다.")));
    }

    @GetMapping("/me")
    @Operation(
            operationId = "getMe",
            summary = "현재 사용자 조회",
            description = "Authorization Bearer 액세스 토큰으로 인증된 현재 사용자의 계정 정보를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 사용자 조회 성공"),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<MemberResponse> getMe(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member
    ) {
        return CommonResponse.success(MemberResponse.from(member));
    }

    private ResponseEntity<CommonResponse<AuthTokenResponse>> tokenResponse(AuthTokenIssueResult result) {
        return tokenResponse(result, null);
    }

    private ResponseEntity<CommonResponse<AuthTokenResponse>> tokenResponse(
            AuthTokenIssueResult result,
            String message
    ) {
        ResponseCookie refreshTokenCookie = refreshTokenCookieFactory.create(result.refreshToken());
        CommonResponse<AuthTokenResponse> response = message == null
                ? CommonResponse.success(result.tokenResponse())
                : CommonResponse.success(message, result.tokenResponse());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(response);
    }
}
