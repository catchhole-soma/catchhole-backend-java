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
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisGuideResponse;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisGuideService;
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
@RequestMapping("/api/v1/analysis-mode-guides")
@Tag(name = "AnalysisGuide", description = "계정별 최초 분석 방식 안내")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "200", description = "안내 대상 또는 일회 노출 결과"),
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
})
public class AnalysisGuideController {
    private final AnalysisGuideService service;

    @GetMapping
    @Operation(operationId = "getMyAnalysisGuide", summary = "첫 분석 안내 대상 조회", description = "계정 전체 분석 이력이 0건이고 아직 안내하지 않은 경우 true. 조회는 상태를 변경하지 않습니다.")
    public CommonResponse<AnalysisGuideResponse> getMyAnalysisGuide(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return CommonResponse.success(service.getGuide(member.memberId()));
    }

    @PostMapping("/claim")
    @Operation(operationId = "claimMyAnalysisGuide", summary = "첫 분석 안내 일회 노출", description = "회원 잠금 아래 자격을 다시 검사하고 계정당 한 번 기록합니다. true를 반환한 요청만 자동 안내합니다. 재요청은 false입니다.")
    public CommonResponse<AnalysisGuideResponse> claimMyAnalysisGuide(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return CommonResponse.success(service.claimGuide(member.memberId()));
    }
}
