package org.monitoring.catchholebackend.domain.worldimage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageThemeResponse;
import org.monitoring.catchholebackend.domain.worldimage.service.WorldImageService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/world-image-theme")
@Tag(name = "WorldImageTheme", description = "작품 장르별 공용 이미지 구성")
@SecurityRequirement(name = "bearerAuth")
public class WorldImageThemeController {
    private final WorldImageService service;

    @GetMapping
    @Operation(operationId = "getWorldImageTheme", summary = "작품 장르별 초기·기본 이미지 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 구성 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "접근 가능한 작품 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldImageThemeResponse> getWorldImageTheme(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId) {
        return CommonResponse.success(service.getImageTheme(member.memberId(), workId));
    }
}
