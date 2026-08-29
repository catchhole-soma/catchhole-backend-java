package org.monitoring.catchholebackend.domain.worldsetting.controller;

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
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDecisionUpdateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateDismissRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateListResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateDecisionUpdateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingTokenInterruptedResumeResponse;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateGroupConfirmResult;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateService;
import org.monitoring.catchholebackend.domain.worldsetting.service.WorldSettingCandidateConfirmResult;
import org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/works/{workId}/world-setting-candidates",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "WorldSettingCandidate", description = "작품별 세계관 설정 후보 조회, 재비교, 확정, 제외 API")
@SecurityRequirement(name = "bearerAuth")
public class WorldSettingCandidateController {

    private final WorldSettingCandidateService candidateService;

    @GetMapping
    @Operation(
            operationId = "getWorldSettingCandidates",
            summary = "세계관 설정 후보 목록 조회",
            description = "한 업로드 묶음의 세계관 후보 집계와 필터된 후보 페이지를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "필수 query 또는 페이지 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 업로드 묶음을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateListResponse> getWorldSettingCandidates(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @RequestParam UUID batchId,
            @RequestParam(required = false) WorldSettingReviewStatus reviewStatus,
            @RequestParam(required = false) WorldSettingCategory category,
            @RequestParam(required = false) WorldSettingSuggestedOperation operation,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return CommonResponse.success(candidateService.getCandidates(
                member.memberId(), workId, batchId, reviewStatus, category, operation, page, size
        ));
    }

    @GetMapping("/{candidateId}")
    @Operation(operationId = "getWorldSettingCandidate", summary = "세계관 설정 후보 상세 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품, 묶음 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateResponse> getWorldSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @RequestParam UUID batchId
    ) {
        return CommonResponse.success(candidateService.getCandidate(
                member.memberId(), workId, batchId, candidateId
        ));
    }

    @PatchMapping(value = "/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "updateWorldSettingCandidateDecisions",
            summary = "세계관 설정 후보 작가 수정안 저장",
            description = "검토 대기 후보의 최종 결정을 저장하고 비교 상태를 유지합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 수정안 저장 성공"),
            @ApiResponse(responseCode = "400", description = "후보 선택 또는 입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품, 묶음 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "검토 또는 비교 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateDecisionUpdateResponse> updateWorldSettingCandidateDecisions(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody WorldSettingCandidateDecisionUpdateRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정 후보 수정안이 저장되었습니다.",
                candidateService.updateCandidateDecisions(member.memberId(), workId, request)
        );
    }

    @PostMapping("/{candidateId}/recompare")
    @Operation(
            operationId = "retryWorldSettingCandidateComparison",
            summary = "세계관 설정 후보 비교 재시도",
            description = "실패 또는 재비교 필요 후보의 기존 비교 결과를 비우고 비교 대기 상태로 전환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 재비교 대기 전환 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "검토 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateResponse> retryWorldSettingCandidateComparison(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId
    ) {
        return CommonResponse.success(
                "세계관 설정 후보가 재비교 대기 상태로 전환되었습니다.",
                candidateService.retryComparison(member.memberId(), workId, candidateId)
        );
    }

    @PostMapping("/batches/{batchId}/resume-token-interrupted")
    @Operation(
            operationId = "resumeTokenInterruptedWorldSettingComparisons",
            summary = "토큰 부족으로 중단된 세계관 비교 일괄 재개",
            description = "해당 배치에서 토큰 부족 코드로 중단된 미검토 후보만 기존 후보 그대로 재개합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "일괄 재개 요청 성공"),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 업로드 묶음을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "최소 비교 예약 토큰 부족",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingTokenInterruptedResumeResponse>
            resumeTokenInterruptedWorldSettingComparisons(
                    @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
                    @PathVariable UUID workId,
                    @PathVariable UUID batchId
            ) {
        return CommonResponse.success(
                "토큰 부족으로 중단된 세계관 비교를 재개했습니다.",
                candidateService.resumeTokenInterruptedComparisons(member.memberId(), workId, batchId)
        );
    }

    @PostMapping(value = "/group-confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "confirmWorldSettingCandidateGroup",
            summary = "세계관 설정 후보 대상 그룹 확정",
            description = "같은 분류·대상의 선택 key를 한 트랜잭션과 한 version 증가로 확정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 그룹 확정 성공"),
            @ApiResponse(responseCode = "400", description = "그룹 또는 최종 결정 입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품, 묶음 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "검토 상태 충돌 또는 ROW/GROUP 재비교 필요",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateGroupActionResponse> confirmWorldSettingCandidateGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody WorldSettingCandidateGroupConfirmRequest request
    ) {
        WorldSettingCandidateGroupConfirmResult result = candidateService.confirmCandidateGroup(
                member.memberId(),
                workId,
                request
        );
        if (result.recomparisonRequired()) {
            throw new AppException(
                    WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED,
                    Map.of(
                            "scope", result.scope(),
                            "reason", result.reason(),
                            "reasonMessage", result.reason().getMessage(),
                            "affectedCandidateIds", result.affectedCandidateIds()
                    )
            );
        }
        return CommonResponse.success(
                "세계관 설정 후보 그룹이 확정되었습니다.",
                result.response()
        );
    }

    @PostMapping(value = "/group-dismiss", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "dismissWorldSettingCandidateGroup",
            summary = "세계관 설정 후보 대상 그룹 제외",
            description = "같은 분류·대상의 선택 key를 한 트랜잭션으로 제외합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 그룹 제외 성공"),
            @ApiResponse(responseCode = "400", description = "그룹 또는 후보 입력값 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품, 묶음 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "검토 또는 비교 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateGroupActionResponse> dismissWorldSettingCandidateGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody WorldSettingCandidateGroupDismissRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정 후보 그룹이 제외되었습니다.",
                candidateService.dismissCandidateGroup(member.memberId(), workId, request)
        );
    }

    @PostMapping(value = "/{candidateId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "confirmWorldSettingCandidate",
            summary = "세계관 설정 후보 확정",
            description = "동일 설정명의 현재값을 비교한 뒤 설정 한 개만 원자적으로 반영합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 확정 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 또는 반영 방식 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "검토 상태 충돌 또는 재비교 필요",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateResponse> confirmWorldSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @Valid @RequestBody WorldSettingCandidateConfirmRequest request
    ) {
        WorldSettingCandidateConfirmResult result = candidateService.confirmCandidate(
                member.memberId(), workId, candidateId, request
        );
        if (result.recomparisonRequired()) {
            throw new AppException(WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_RECOMPARISON_REQUIRED);
        }
        return CommonResponse.success(
                "세계관 설정 후보가 확정되었습니다.",
                result.candidate()
        );
    }

    @PostMapping(value = "/{candidateId}/dismiss", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "dismissWorldSettingCandidate", summary = "세계관 설정 후보 제외")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세계관 설정 후보 제외 성공"),
            @ApiResponse(responseCode = "404", description = "작품 또는 후보를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미 확정된 후보와 상태 충돌",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingCandidateResponse> dismissWorldSettingCandidate(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID candidateId,
            @Valid @RequestBody WorldSettingCandidateDismissRequest request
    ) {
        return CommonResponse.success(
                "세계관 설정 후보가 제외되었습니다.",
                candidateService.dismissCandidate(member.memberId(), workId, candidateId, request)
        );
    }
}
