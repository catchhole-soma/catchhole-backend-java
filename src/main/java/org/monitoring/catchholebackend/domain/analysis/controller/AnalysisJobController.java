package org.monitoring.catchholebackend.domain.analysis.controller;

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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService;
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
        value = "/api/v1/works/{workId}/analysis-jobs",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@Tag(name = "AnalysisJob", description = "로그인한 사용자의 작품별 AI 분석 작업 생성 및 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class AnalysisJobController {

    private final AnalysisJobService analysisJobService;

    @PostMapping
    @Operation(
            operationId = "createAnalysisJob",
            summary = "회차별 분석 작업 생성",
            description = "로그인한 사용자가 본인 작품의 업로드 배치에 포함된 각 회차마다 AI 분석 작업을 생성합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 생성 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 분석 대상 리소스를 찾을 수 없음")
    })
    public CommonResponse<List<AnalysisJobResponse>> createAnalysisJob(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody AnalysisJobCreateRequest request
    ) {
        return CommonResponse.success(
                "분석 작업이 생성되었습니다.",
                analysisJobService.createAnalysisJobs(member.memberId(), workId, request)
        );
    }

    @GetMapping
    @Operation(
            operationId = "getAnalysisJobs",
            summary = "분석 작업 목록 조회",
            description = "로그인한 사용자가 본인 작품에 생성한 분석 작업 목록을 최신 생성순으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품을 찾을 수 없음")
    })
    public CommonResponse<List<AnalysisJobResponse>> getAnalysisJobs(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId
    ) {
        return CommonResponse.success(analysisJobService.getAnalysisJobs(member.memberId(), workId));
    }

    @GetMapping("/batches")
    @Operation(
            operationId = "getAnalysisBatches",
            summary = "분석 배치 목록 조회",
            description = "업로드 배치별 최신 유효 분석 작업과 설정 후보 검토 현황을 "
                    + "최근 분석 요청순으로 페이지 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 배치 목록 조회 성공"),
            @ApiResponse(
                    responseCode = "400",
                    description = "페이지 번호 또는 크기 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "액세스 토큰 없음, 만료 또는 검증 실패",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "작품을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))
            )
    })
    public CommonResponse<PageResponse<AnalysisBatchSummaryResponse>> getAnalysisBatches(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Parameter(
                    name = "page",
                    in = ParameterIn.QUERY,
                    description = "0부터 시작하는 페이지 번호",
                    example = "0"
            )
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.")
            int page,
            @Parameter(
                    name = "size",
                    in = ParameterIn.QUERY,
                    description = "페이지 크기. 분석 목록은 기본 10개이며 1~20 사이로 요청합니다.",
                    example = "10"
            )
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
            @Max(value = 20, message = "페이지 크기는 20 이하여야 합니다.")
            int size
    ) {
        return CommonResponse.success(
                analysisJobService.getAnalysisBatches(member.memberId(), workId, page, size)
        );
    }

    @GetMapping("/{analysisJobId}")
    @Operation(
            operationId = "getAnalysisJob",
            summary = "분석 작업 상세 조회",
            description = "로그인한 사용자가 본인 작품에 생성한 특정 분석 작업의 상태와 결과 요약을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 분석 작업을 찾을 수 없음")
    })
    public CommonResponse<AnalysisJobResponse> getAnalysisJob(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID analysisJobId
    ) {
        return CommonResponse.success(
                analysisJobService.getAnalysisJob(member.memberId(), workId, analysisJobId)
        );
    }

    @PostMapping("/{analysisJobId}/retry")
    @Operation(
            operationId = "retryAnalysisJob",
            summary = "실패 회차 분석 재시도",
            description = "기존 실패 작업은 유지하고 서버가 확인한 FAILED 회차만 새 분석 작업으로 생성합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실패 회차 재시도 작업 생성 성공"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품, 분석 작업 또는 실패 회차를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "실패 상태가 아니거나 같은 batch의 전체 작업이 진행 중")
    })
    public CommonResponse<List<AnalysisJobResponse>> retryAnalysisJob(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID analysisJobId
    ) {
        return CommonResponse.success(
                "실패한 회차의 분석을 다시 요청했습니다.",
                analysisJobService.retryFailedAnalysisJob(member.memberId(), workId, analysisJobId)
        );
    }
}
