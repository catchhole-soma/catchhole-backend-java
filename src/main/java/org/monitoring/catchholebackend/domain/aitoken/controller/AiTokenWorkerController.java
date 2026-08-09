package org.monitoring.catchholebackend.domain.aitoken.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReleaseRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenSettleRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/internal/v1/ai-token-usages", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal AI Token", description = "AI Worker 요청별 토큰 예약·정산 API")
@SecurityRequirement(name = "internalApiKey")
public class AiTokenWorkerController {

    private final AiTokenService aiTokenService;

    @PostMapping("/reserve")
    @Operation(
            operationId = "reserveAiTokens",
            summary = "AI 요청 토큰 예약",
            description = "Worker가 provider 호출 전에 요청 단위 예상 토큰을 예약합니다. 같은 requestId 재호출은 기존 예약을 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 토큰 예약 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "내부 API 키 없음 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "분석 작업을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "분석 작업 상태 충돌 또는 남은 사용량 부족",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<AiTokenReservationResponse> reserve(
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody AiTokenReserveRequest request
    ) {
        return CommonResponse.success("AI 토큰을 예약했습니다.", aiTokenService.reserve(request, leaseToken));
    }

    @PostMapping("/{requestId}/settle")
    @Operation(
            operationId = "settleAiTokens",
            summary = "AI 요청 실제 토큰 정산",
            description = "provider 응답의 실제 입력·캐시 입력·출력 토큰으로 예약을 확정 정산합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 토큰 정산 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 또는 토큰 사용량 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "내부 API 키 없음 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "토큰 예약 또는 분석 작업을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 해제된 예약 등 정산 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<Void> settle(
            @PathVariable UUID requestId,
            @Valid @RequestBody AiTokenSettleRequest request
    ) {
        aiTokenService.settle(requestId, request);
        return CommonResponse.success("AI 토큰 사용량을 정산했습니다.", null);
    }

    @PostMapping("/{requestId}/release")
    @Operation(
            operationId = "releaseAiTokens",
            summary = "사용되지 않은 AI 토큰 예약 해제",
            description = "provider 사용량을 얻지 못한 요청의 예약량을 사용자 계정으로 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 토큰 예약 해제 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 값 또는 해제 사유 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "내부 API 키 없음 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "토큰 예약 또는 분석 작업을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 정산된 예약 등 해제 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<Void> release(
            @PathVariable UUID requestId,
            @Valid @RequestBody AiTokenReleaseRequest request
    ) {
        aiTokenService.release(requestId, request);
        return CommonResponse.success("AI 토큰 예약을 해제했습니다.", null);
    }
}
