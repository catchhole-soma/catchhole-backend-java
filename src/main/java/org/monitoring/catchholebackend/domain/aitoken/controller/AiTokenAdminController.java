package org.monitoring.catchholebackend.domain.aitoken.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenExtensionRejectRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenExtensionAdminResponse;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenExtensionStatus;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/admin/ai-token-extension-requests",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "AI Token Admin", description = "운영자용 추가 AI 사용량 요청 처리 API")
@SecurityRequirement(name = "bearerAuth")
public class AiTokenAdminController {

    private final AiTokenService aiTokenService;

    @GetMapping
    @Operation(
            operationId = "getAiTokenExtensionRequestsForAdmin",
            summary = "추가 AI 사용량 요청 목록 조회",
            description = "상태별 요청을 오래된 순서로 조회합니다. 기본 상태는 PENDING입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<PageResponse<AiTokenExtensionAdminResponse>> getAiTokenExtensionRequestsForAdmin(
            @RequestParam(defaultValue = "PENDING") AiTokenExtensionStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return CommonResponse.success(aiTokenService.getExtensionRequests(status, page, size));
    }

    @GetMapping("/{requestId}")
    @Operation(operationId = "getAiTokenExtensionRequestForAdmin", summary = "추가 AI 사용량 요청 상세 조회")
    public CommonResponse<AiTokenExtensionAdminResponse> getAiTokenExtensionRequestForAdmin(
            @PathVariable UUID requestId
    ) {
        return CommonResponse.success(aiTokenService.getExtensionRequest(requestId));
    }

    @PostMapping("/{requestId}/approve")
    @Operation(
            operationId = "approveAiTokenExtensionRequest",
            summary = "추가 AI 사용량 요청 승인",
            description = "요청 본문에서 지급량을 받지 않고 현재 AI_TOKEN_DEFAULT_GRANT를 한 번 지급합니다. 반복 호출은 같은 승인 결과를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 및 추가 사용량 지급 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "요청을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미 거절된 요청 또는 지급 설정 비활성화",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<AiTokenExtensionAdminResponse> approveAiTokenExtensionRequest(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal reviewer,
            @PathVariable UUID requestId
    ) {
        return CommonResponse.success(
                "추가 사용량이 지급되었습니다.",
                aiTokenService.approveExtensionRequest(reviewer.memberId(), requestId)
        );
    }

    @PostMapping(value = "/{requestId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectAiTokenExtensionRequest", summary = "추가 AI 사용량 요청 거절")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 거절 성공"),
            @ApiResponse(responseCode = "400", description = "거절 사유 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "관리자 권한 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "요청을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미 승인된 요청",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<AiTokenExtensionAdminResponse> rejectAiTokenExtensionRequest(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal reviewer,
            @PathVariable UUID requestId,
            @Valid @RequestBody AiTokenExtensionRejectRequest request
    ) {
        return CommonResponse.success(
                "추가 사용량 요청이 거절되었습니다.",
                aiTokenService.rejectExtensionRequest(reviewer.memberId(), requestId, request)
        );
    }
}
