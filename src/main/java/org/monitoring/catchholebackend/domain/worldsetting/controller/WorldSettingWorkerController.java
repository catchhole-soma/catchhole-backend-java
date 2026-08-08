package org.monitoring.catchholebackend.domain.worldsetting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingCandidatePublishRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonContextRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonFailRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingWorkerService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/analysis-jobs/{analysisJobId}")
@Tag(name = "Internal WorldSetting Worker", description = "AI Worker 세계관 설정 후보 생성 및 비교 API")
@SecurityRequirement(name = "internalApiKey")
public class WorldSettingWorkerController {

    private final WorldSettingWorkerService worldSettingWorkerService;

    @PutMapping("/world-setting-candidates")
    @Operation(operationId = "publishWorkerWorldSettingCandidates", summary = "세계관 설정 1차 추출 후보 게시")
    public CommonResponse<List<WorkerWorldSettingCandidatePayload>> publishWorldSettingCandidates(
            @PathVariable UUID analysisJobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody WorkerWorldSettingCandidatePublishRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정 후보가 게시되었습니다.",
                worldSettingWorkerService.publishWorldSettingCandidates(
                        analysisJobId,
                        leaseToken,
                        request
                )
        );
    }

    @PostMapping("/world-setting-comparisons/claim-next")
    @Operation(operationId = "claimNextWorkerWorldSettingComparison", summary = "다음 세계관 설정 후보 비교 claim")
    public ResponseEntity<CommonResponse<WorkerWorldSettingCandidatePayload>> claimNextWorldSettingComparison(
            @PathVariable UUID analysisJobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken
    ) {
        Optional<WorkerWorldSettingCandidatePayload> candidate =
                worldSettingWorkerService.claimNextWorldSettingComparison(
                        analysisJobId,
                        leaseToken
                );
        return candidate
                .map(value -> ResponseEntity.ok(CommonResponse.success("세계관 설정 비교를 claim했습니다.", value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/world-setting-subjects")
    @Operation(operationId = "getWorkerWorldSettingSubjects", summary = "세계관 설정 대상명 목록 조회")
    public CommonResponse<WorkerWorldSettingSubjectPageResponse> getWorldSettingSubjects(
            @PathVariable UUID analysisJobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @RequestParam WorldSettingCategory category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "500") @Min(1) @Max(500) int size
    ) {
        return CommonResponse.success(
                "세계관 설정 대상명 목록을 조회했습니다.",
                worldSettingWorkerService.getWorldSettingSubjects(
                        analysisJobId,
                        leaseToken,
                        category,
                        page,
                        size
                )
        );
    }

    @PostMapping("/world-setting-candidates/{candidateId}/comparison-context")
    @Operation(operationId = "getWorkerWorldSettingComparisonContext", summary = "세계관 설정 상세 비교 문맥 조회")
    public CommonResponse<WorkerWorldSettingComparisonContextResponse> getWorldSettingComparisonContext(
            @PathVariable UUID analysisJobId,
            @PathVariable UUID candidateId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody WorkerWorldSettingComparisonContextRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정 비교 문맥을 조회했습니다.",
                worldSettingWorkerService.getWorldSettingComparisonContext(
                        analysisJobId,
                        candidateId,
                        leaseToken,
                        request
                )
        );
    }

    @PostMapping("/world-setting-candidates/{candidateId}/comparison-complete")
    @Operation(operationId = "completeWorkerWorldSettingComparison", summary = "세계관 설정 비교 완료")
    public CommonResponse<Void> completeWorldSettingComparison(
            @PathVariable UUID analysisJobId,
            @PathVariable UUID candidateId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody WorkerWorldSettingComparisonCompleteRequest request
    ) {
        worldSettingWorkerService.completeWorldSettingComparison(
                analysisJobId,
                candidateId,
                leaseToken,
                request
        );
        return CommonResponse.success("세계관 설정 비교가 완료되었습니다.", null);
    }

    @PostMapping("/world-setting-candidates/{candidateId}/comparison-fail")
    @Operation(operationId = "failWorkerWorldSettingComparison", summary = "세계관 설정 비교 실패")
    public CommonResponse<Void> failWorldSettingComparison(
            @PathVariable UUID analysisJobId,
            @PathVariable UUID candidateId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody WorkerWorldSettingComparisonFailRequest request
    ) {
        worldSettingWorkerService.failWorldSettingComparison(
                analysisJobId,
                candidateId,
                leaseToken,
                request
        );
        return CommonResponse.success("세계관 설정 비교가 실패 처리되었습니다.", null);
    }
}
