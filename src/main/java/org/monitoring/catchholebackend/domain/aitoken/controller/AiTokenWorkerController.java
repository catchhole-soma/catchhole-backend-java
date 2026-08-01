package org.monitoring.catchholebackend.domain.aitoken.controller;

import io.swagger.v3.oas.annotations.Operation;
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
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/ai-token-usages")
@Tag(name = "Internal AI Token", description = "AI Worker 요청별 토큰 예약·정산 API")
@SecurityRequirement(name = "internalApiKey")
public class AiTokenWorkerController {

    private final AiTokenService aiTokenService;

    @PostMapping("/reserve")
    @Operation(operationId = "reserveAiTokens", summary = "AI 요청 토큰 예약")
    public CommonResponse<AiTokenReservationResponse> reserve(
            @Valid @RequestBody AiTokenReserveRequest request
    ) {
        return CommonResponse.success("AI 토큰을 예약했습니다.", aiTokenService.reserve(request));
    }

    @PostMapping("/{requestId}/settle")
    @Operation(operationId = "settleAiTokens", summary = "AI 요청 실제 토큰 정산")
    public CommonResponse<Void> settle(
            @PathVariable UUID requestId,
            @Valid @RequestBody AiTokenSettleRequest request
    ) {
        aiTokenService.settle(requestId, request);
        return CommonResponse.success("AI 토큰 사용량을 정산했습니다.", null);
    }

    @PostMapping("/{requestId}/release")
    @Operation(operationId = "releaseAiTokens", summary = "사용되지 않은 AI 토큰 예약 해제")
    public CommonResponse<Void> release(
            @PathVariable UUID requestId,
            @Valid @RequestBody AiTokenReleaseRequest request
    ) {
        aiTokenService.release(requestId, request);
        return CommonResponse.success("AI 토큰 예약을 해제했습니다.", null);
    }
}
