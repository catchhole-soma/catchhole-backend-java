package org.monitoring.catchholebackend.domain.relationship.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobHeartbeatResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipBackfillCoordinator;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/relationship-jobs")
@Tag(name = "Internal Relationship Backfill Worker")
@SecurityRequirement(name = "internalApiKey")
public class RelationshipBackfillWorkerController {
    private final RelationshipBackfillCoordinator coordinator;

    @PostMapping("/claim")
    @Operation(summary = "관계 전용 회차 순서 작업 선점")
    public ResponseEntity<CommonResponse<WorkerAnalysisJobPayload>> claim(
            @Valid @RequestBody WorkerAnalysisJobClaimRequest request) {
        return coordinator
                .claim(request)
                .map(p -> ResponseEntity.ok(CommonResponse.success("관계 작업을 claim했습니다.", p)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/heartbeat")
    @Operation(summary = "관계 전용 작업 lease 갱신")
    public CommonResponse<WorkerAnalysisJobHeartbeatResponse> heartbeat(
            @PathVariable UUID id,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease) {
        return CommonResponse.success("관계 작업 lease가 갱신되었습니다.", coordinator.heartbeat(id, lease));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "관계 전용 작업 자동 반영 및 완료")
    public CommonResponse<Void> complete(
            @PathVariable UUID id,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @RequestBody(required = false) WorkerAnalysisJobCompleteRequest request) {
        coordinator.complete(id, lease, request);
        return CommonResponse.success("관계 작업을 완료했습니다.", null);
    }

    @PostMapping("/{id}/fail")
    @Operation(summary = "관계 전용 작업 실패 기록")
    public CommonResponse<Void> fail(
            @PathVariable UUID id,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @Valid @RequestBody WorkerAnalysisJobFailRequest request) {
        coordinator.fail(id, lease, request);
        return CommonResponse.success("관계 작업 실패를 기록했습니다.", null);
    }
}
