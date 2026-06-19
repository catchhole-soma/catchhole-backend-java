package org.monitoring.catchholebackend.domain.analysis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobWorkerService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/analysis-jobs")
@Tag(name = "Internal AnalysisJob Worker", description = "AI Worker 내부 분석 작업 claim 및 상태 변경 API")
public class AnalysisJobWorkerController {

    private final AnalysisJobWorkerService analysisJobWorkerService;

    @PostMapping("/claim")
    @Operation(summary = "AI Worker 분석 작업 claim")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 claim 성공"),
            @ApiResponse(responseCode = "204", description = "claim할 분석 작업 없음"),
            @ApiResponse(responseCode = "401", description = "내부 API Key 없음 또는 검증 실패")
    })
    public ResponseEntity<CommonResponse<WorkerAnalysisJobPayload>> claimAnalysisJob(
            @Valid @RequestBody(required = false) WorkerAnalysisJobClaimRequest request
    ) {
        Optional<WorkerAnalysisJobPayload> payload = analysisJobWorkerService.claimAnalysisJob(request);
        return payload
                .map(value -> ResponseEntity.ok(CommonResponse.success("분석 작업을 claim했습니다.", value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PatchMapping("/{analysisJobId}/progress")
    @Operation(summary = "AI Worker 분석 작업 진행 단계 갱신")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "진행 단계 갱신 성공"),
            @ApiResponse(responseCode = "401", description = "내부 API Key 없음 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "분석 작업을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "분석 작업 상태 충돌")
    })
    public CommonResponse<Void> updateProgress(
            @PathVariable UUID analysisJobId,
            @Valid @RequestBody WorkerAnalysisJobProgressRequest request
    ) {
        analysisJobWorkerService.updateProgress(analysisJobId, request);
        return CommonResponse.success("분석 작업 진행 단계가 갱신되었습니다.", null);
    }

    @PostMapping("/{analysisJobId}/complete")
    @Operation(summary = "AI Worker 분석 작업 완료")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 완료 처리 성공"),
            @ApiResponse(responseCode = "401", description = "내부 API Key 없음 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "분석 작업을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "분석 작업 상태 충돌")
    })
    public CommonResponse<Void> completeAnalysisJob(
            @PathVariable UUID analysisJobId,
            @Valid @RequestBody(required = false) WorkerAnalysisJobCompleteRequest request
    ) {
        analysisJobWorkerService.completeAnalysisJob(
                analysisJobId,
                request == null ? new WorkerAnalysisJobCompleteRequest(null, null, null) : request
        );
        return CommonResponse.success("분석 작업이 완료 처리되었습니다.", null);
    }

    @PostMapping("/{analysisJobId}/fail")
    @Operation(summary = "AI Worker 분석 작업 실패")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 실패 처리 성공"),
            @ApiResponse(responseCode = "401", description = "내부 API Key 없음 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "분석 작업을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "분석 작업 상태 충돌")
    })
    public CommonResponse<Void> failAnalysisJob(
            @PathVariable UUID analysisJobId,
            @Valid @RequestBody WorkerAnalysisJobFailRequest request
    ) {
        analysisJobWorkerService.failAnalysisJob(analysisJobId, request);
        return CommonResponse.success("분석 작업이 실패 처리되었습니다.", null);
    }
}
