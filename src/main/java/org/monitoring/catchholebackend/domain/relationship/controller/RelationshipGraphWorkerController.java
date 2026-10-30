package org.monitoring.catchholebackend.domain.relationship.controller;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Complete;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Fail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.relationship.service.RelationshipGraphService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/analysis-jobs/{jobId}/relationship-analysis")
@Tag(name = "Internal Relationship Analysis", description = "회차 관계 추출과 비교 결과를 lease 아래 저장합니다.")
@SecurityRequirement(name = "internalApiKey")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "내부 인증 실패"),
    @ApiResponse(responseCode = "409", description = "lease·원문·입력 충돌")
})
public class RelationshipGraphWorkerController {
    private final RelationshipGraphService service;

    @GetMapping
    @Operation(
            operationId = "getWorkerRelationshipAnalysis",
            summary = "저장된 관계 분석 재개 문맥 조회",
            description = "추출 전에는 204입니다. 재개 시 완료된 유료 추출을 반복하지 않습니다.")
    public ResponseEntity<CommonResponse<Context>> getRelationshipAnalysis(
            @PathVariable UUID jobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease) {
        return service.getRelationshipAnalysis(jobId, lease)
                .map(c -> ResponseEntity.ok(CommonResponse.success(c)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/prepare")
    @Operation(
            operationId = "prepareWorkerRelationshipAnalysis",
            summary = "관계 비교 문맥 고정",
            description = "원문 hash·인용 위치를 검증하고 현재 회차 이전 상태만 제공합니다.")
    public CommonResponse<Context> prepareRelationshipAnalysis(
            @PathVariable UUID jobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @RequestBody Prepare request) {
        return CommonResponse.success(service.prepareRelationshipAnalysis(jobId, lease, request));
    }

    @PostMapping("/complete")
    @Operation(
            operationId = "completeWorkerRelationshipAnalysis",
            summary = "검증된 관계 결정 저장",
            description = "전체 신규 관측 coverage와 주체·대상 참조를 검증합니다. 원 Job 완료 시 반영합니다.")
    public CommonResponse<Void> completeRelationshipAnalysis(
            @PathVariable UUID jobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @RequestBody Complete request) {
        service.completeRelationshipAnalysis(jobId, lease, request);
        return CommonResponse.success(null);
    }

    @PostMapping("/fail")
    @Operation(
            operationId = "failWorkerRelationshipAnalysis",
            summary = "관계 의미 분석 실패 기록",
            description = "캐릭터·세계관 결과를 보존합니다. 인증·토큰·원문·저장 장애는 이 실패로 우회하지 않습니다.")
    public CommonResponse<Void> failRelationshipAnalysis(
            @PathVariable UUID jobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @RequestBody Fail request) {
        service.failRelationshipAnalysis(jobId, lease, request);
        return CommonResponse.success(null);
    }
}
