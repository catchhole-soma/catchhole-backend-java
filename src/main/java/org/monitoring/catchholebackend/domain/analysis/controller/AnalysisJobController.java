package org.monitoring.catchholebackend.domain.analysis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobService;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/analysis-jobs")
@Tag(name = "AnalysisJob", description = "로그인한 사용자의 작품별 AI 분석 작업 생성 및 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class AnalysisJobController {

    private final AnalysisJobService analysisJobService;

    @PostMapping
    @Operation(
            summary = "분석 작업 생성",
            description = "로그인한 사용자가 본인 작품의 업로드 배치를 대상으로 AI 분석 작업을 생성합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 작업 생성 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "액세스 토큰 없음, 만료 또는 검증 실패"),
            @ApiResponse(responseCode = "404", description = "작품 또는 분석 대상 리소스를 찾을 수 없음")
    })
    public CommonResponse<AnalysisJobResponse> createAnalysisJob(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody AnalysisJobCreateRequest request
    ) {
        return CommonResponse.success(
                "분석 작업이 생성되었습니다.",
                analysisJobService.createAnalysisJob(member.memberId(), workId, request)
        );
    }

    @GetMapping
    @Operation(
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

    @GetMapping("/{analysisJobId}")
    @Operation(
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
}
