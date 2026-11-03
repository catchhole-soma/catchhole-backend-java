package org.monitoring.catchholebackend.domain.analysis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisStartCheckRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisStartCheckResponse;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisStartCheckService;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/works/{workId}/analysis-start-checks", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AnalysisJob")
@SecurityRequirement(name = "bearerAuth")
public class AnalysisStartCheckController {
    private final AnalysisStartCheckService service;

    @PostMapping
    @Operation(operationId = "checkAnalysisStart", summary = "다음 회차 분석 전 미확정 후보 확인",
            description = "이전 회차의 직접 검토 필수 후보와 종료 가능한 정상 보류 후보를 구분합니다. 이 요청은 후보나 분석을 변경하지 않습니다.")
    public CommonResponse<AnalysisStartCheckResponse> checkAnalysisStart(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody AnalysisStartCheckRequest request) {
        return CommonResponse.success(service.checkAnalysisStart(member.memberId(), workId, request));
    }
}
