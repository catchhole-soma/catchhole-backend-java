package org.monitoring.catchholebackend.domain.aitoken.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenUsageResponse;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/ai-token-usages", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI Token", description = "로그인 사용자의 AI 토큰 사용량 API")
@SecurityRequirement(name = "bearerAuth")
public class AiTokenController {

    private final AiTokenService aiTokenService;

    @GetMapping("/me")
    @Operation(
            operationId = "getMyAiTokenUsage",
            summary = "내 AI 토큰 사용량 조회",
            description = "로그인한 사용자의 지급량, 확정 사용량, 처리 중 예약량과 남은 사용량을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 토큰 사용량 조회 성공"),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<AiTokenUsageResponse> getMyAiTokenUsage(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member
    ) {
        return CommonResponse.success(aiTokenService.getUsage(member.memberId()));
    }
}
