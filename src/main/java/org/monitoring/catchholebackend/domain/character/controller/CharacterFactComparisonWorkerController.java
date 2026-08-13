package org.monitoring.catchholebackend.domain.character.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonCompleteRequest;
import org.monitoring.catchholebackend.domain.character.dto.request.WorkerCharacterFactComparisonFailRequest;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonCandidatePayload;
import org.monitoring.catchholebackend.domain.character.dto.response.WorkerCharacterFactComparisonContextResponse;
import org.monitoring.catchholebackend.domain.character.service.CharacterFactComparisonWorkerService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/v1/analysis-jobs/{analysisJobId}")
@Tag(name = "Internal Character Fact Comparison Worker", description = "AI Worker 캐릭터 Fact 비교 API")
@SecurityRequirement(name = "internalApiKey")
public class CharacterFactComparisonWorkerController {

    private final CharacterFactComparisonWorkerService service;

    @PostMapping("/character-fact-comparisons/claim-next")
    @Operation(operationId = "claimNextWorkerCharacterFactComparison", summary = "다음 캐릭터 Fact 비교 claim")
    public ResponseEntity<CommonResponse<WorkerCharacterFactComparisonCandidatePayload>> claimNext(
            @PathVariable UUID analysisJobId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken
    ) {
        Optional<WorkerCharacterFactComparisonCandidatePayload> candidate =
                service.claimNextCharacterFactComparison(
                analysisJobId,
                leaseToken
        );
        return candidate
                .map(value -> ResponseEntity.ok(CommonResponse.success("캐릭터 Fact 비교를 claim했습니다.", value)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/setting-candidates/{candidateId}/character-fact-comparison-context")
    @Operation(operationId = "getWorkerCharacterFactComparisonContext", summary = "캐릭터 Fact 비교 문맥 조회")
    public CommonResponse<WorkerCharacterFactComparisonContextResponse> getContext(
            @PathVariable UUID analysisJobId,
            @PathVariable UUID candidateId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken
    ) {
        return CommonResponse.success(
                "캐릭터 Fact 비교 문맥을 조회했습니다.",
                service.getCharacterFactComparisonContext(analysisJobId, candidateId, leaseToken)
        );
    }

    @PostMapping("/setting-candidates/{candidateId}/character-fact-comparison-complete")
    @Operation(operationId = "completeWorkerCharacterFactComparison", summary = "캐릭터 Fact 비교 완료")
    public CommonResponse<Void> complete(
            @PathVariable UUID analysisJobId,
            @PathVariable UUID candidateId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody WorkerCharacterFactComparisonCompleteRequest request
    ) {
        service.completeCharacterFactComparison(analysisJobId, candidateId, leaseToken, request);
        return CommonResponse.success("캐릭터 Fact 비교가 완료되었습니다.", null);
    }

    @PostMapping("/setting-candidates/{candidateId}/character-fact-comparison-fail")
    @Operation(operationId = "failWorkerCharacterFactComparison", summary = "캐릭터 Fact 비교 실패")
    public CommonResponse<Void> fail(
            @PathVariable UUID analysisJobId,
            @PathVariable UUID candidateId,
            @RequestHeader(SecurityConstant.WORKER_LEASE_TOKEN_HEADER) UUID leaseToken,
            @Valid @RequestBody WorkerCharacterFactComparisonFailRequest request
    ) {
        service.failCharacterFactComparison(analysisJobId, candidateId, leaseToken, request);
        return CommonResponse.success("캐릭터 Fact 비교가 실패 처리되었습니다.", null);
    }
}
