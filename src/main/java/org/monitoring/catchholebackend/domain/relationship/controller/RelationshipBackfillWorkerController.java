package org.monitoring.catchholebackend.domain.relationship.controller;

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

import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobHeartbeatResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipBackfillService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
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
@Tag(name = "Internal Relationship Backfill Worker", description = "선정 계정의 관계 전용 작업 선점과 임대·완료 상태를 관리합니다.")
@SecurityRequirement(name = "internalApiKey")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "요청 형식 또는 필수 입력 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "내부 API 키가 없거나 유효하지 않음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "작업 또는 작품을 찾을 수 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "작업 상태·임대·원문·입력 충돌", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
})
public class RelationshipBackfillWorkerController {
    private final RelationshipBackfillService coordinator;

    @PostMapping("/claim")
    @Operation(operationId = "claimRelationshipJob", summary = "관계 전용 회차 순서 작업 선점", description = "선정된 작품의 관계 작업을 회차 순서로 하나 선점합니다. 실행할 작업이 없거나 기능이 중지되면 204를 반환합니다.")
    @ApiResponse(responseCode = "200", description = "관계 작업 선점 성공")
    @ApiResponse(responseCode = "204", description = "선점할 관계 작업 없음", content = @Content)
    public ResponseEntity<CommonResponse<WorkerAnalysisJobPayload>> claimRelationshipJob(
            @Valid @RequestBody WorkerAnalysisJobClaimRequest request) {
        return coordinator
                .claimRelationshipJob(request)
                .map(p -> ResponseEntity.ok(CommonResponse.success("관계 작업을 claim했습니다.", p)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/heartbeat")
    @Operation(operationId = "renewRelationshipJobLease", summary = "관계 전용 작업 lease 갱신", description = "현재 작업의 유효한 임대 토큰을 확인하고 만료 시각을 연장합니다.")
    @ApiResponse(responseCode = "200", description = "관계 작업 임대 갱신 성공")
    public CommonResponse<WorkerAnalysisJobHeartbeatResponse> renewRelationshipJobLease(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = SecurityConstant.WORKER_LEASE_TOKEN_HEADER,
                    description = "관계 작업 선점 시 받은 임대 토큰", required = true)
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease) {
        return CommonResponse.success("관계 작업 lease가 갱신되었습니다.", coordinator.renewRelationshipJobLease(id, lease));
    }

    @PostMapping("/{id}/complete")
    @Operation(operationId = "completeRelationshipJob", summary = "관계 전용 작업 자동 반영 및 완료", description = "원문과 관계 결과를 재검증해 작업을 완료하고, 작품의 대기열이 끝나면 새 관계 세대를 공개합니다.")
    @ApiResponse(responseCode = "200", description = "관계 작업 완료 성공")
    public CommonResponse<Void> completeRelationshipJob(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = SecurityConstant.WORKER_LEASE_TOKEN_HEADER,
                    description = "관계 작업 선점 시 받은 임대 토큰", required = true)
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @RequestBody(required = false) WorkerAnalysisJobCompleteRequest request) {
        coordinator.completeRelationshipJob(id, lease, request);
        return CommonResponse.success("관계 작업을 완료했습니다.", null);
    }

    @PostMapping("/{id}/fail")
    @Operation(operationId = "failRelationshipJob", summary = "관계 전용 작업 실패 기록", description = "관계 작업의 실패를 기록하고 해당 작품의 후행 관계 처리를 차단합니다. 캐릭터·세계관 분석 결과는 보존합니다.")
    @ApiResponse(responseCode = "200", description = "관계 작업 실패 기록 성공")
    public CommonResponse<Void> failRelationshipJob(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = SecurityConstant.WORKER_LEASE_TOKEN_HEADER,
                    description = "관계 작업 선점 시 받은 임대 토큰", required = true)
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID lease,
            @Valid @RequestBody WorkerAnalysisJobFailRequest request) {
        coordinator.failRelationshipJob(id, lease, request);
        return CommonResponse.success("관계 작업 실패를 기록했습니다.", null);
    }
}
