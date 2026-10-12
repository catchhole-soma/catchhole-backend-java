package org.monitoring.catchholebackend.domain.settingtool.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolQuery;
import org.monitoring.catchholebackend.domain.settingtool.dto.SettingToolResponse;
import org.monitoring.catchholebackend.domain.settingtool.service.SettingToolService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/works/{workId}/setting-tools", produces = "application/json")
@Tag(name = "SettingTool", description = "현재 설정·확정 이력·검증된 직접 근거의 읽기 전용 도구")
@SecurityRequirement(name = "bearerAuth")
public class SettingToolController {
    private final SettingToolService service;

    @PostMapping("/queries")
    @Operation(operationId = "querySettingTools", summary = "설정 도구 조회",
            description = "로그인한 회원의 활성 작품만 조회합니다. 회차 범위는 근거의 출처 필터이며 특정 회차의 전체 상태 복원이 아닙니다. 계약: docs/setting-tools-v1.md")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 완료. 빈 목록·partial·evidence.status를 함께 확인합니다."),
            @ApiResponse(responseCode = "400", description = "도구 인자 또는 필터 조합 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "권한 범위에서 조회할 수 없는 대상", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "파기 중인 작품", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<SettingToolResponse> querySettings(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestBody SettingToolQuery request
    ) {
        return CommonResponse.success(service.querySettings(member.memberId(), workId, request));
    }
}
