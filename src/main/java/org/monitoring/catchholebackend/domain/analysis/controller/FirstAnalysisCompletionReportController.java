package org.monitoring.catchholebackend.domain.analysis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.response.FirstAnalysisCompletionReportResponse;
import org.monitoring.catchholebackend.domain.analysis.service.FirstAnalysisCompletionReportService;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/first-analysis-completion-reports")
@Tag(name = "FirstAnalysisCompletionReport", description = "계정별 첫 분석 완료 전환 이벤트 일회 보고")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "보고 대상 또는 일회 선점 결과"),
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
})
public class FirstAnalysisCompletionReportController {
    private final FirstAnalysisCompletionReportService service;

    @GetMapping
    @Operation(operationId = "getMyFirstAnalysisCompletionReport", summary = "첫 분석 완료 보고 대상 조회",
            description = "첫 설정 추출(회차·설정집)을 완료했고 아직 보고하지 않았으면 true. 조회는 상태를 변경하지 않습니다.")
    public CommonResponse<FirstAnalysisCompletionReportResponse> getMyFirstAnalysisCompletionReport(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return CommonResponse.success(service.getFirstAnalysisCompletionReport(member.memberId()));
    }

    @PostMapping("/claim")
    @Operation(operationId = "claimMyFirstAnalysisCompletionReport", summary = "첫 분석 완료 일회 보고 선점",
            description = "회원 잠금 아래 자격을 다시 검사하고 계정당 한 번 기록합니다. true를 반환한 요청만 전환 이벤트를 보냅니다. 재요청과 동시 요청은 false입니다.")
    public CommonResponse<FirstAnalysisCompletionReportResponse> claimMyFirstAnalysisCompletionReport(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return CommonResponse.success(service.claimFirstAnalysisCompletionReport(member.memberId()));
    }
}
