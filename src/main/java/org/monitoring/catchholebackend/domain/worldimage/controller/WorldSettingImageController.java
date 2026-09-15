package org.monitoring.catchholebackend.domain.worldimage.controller;

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
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldSettingImageUpdateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.service.WorldImageService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/world-settings/{worldSettingId}/image")
@Tag(name = "WorldSettingImage", description = "작품별 세계관 대표 이미지 선택")
@SecurityRequirement(name = "bearerAuth")
public class WorldSettingImageController {
    private final WorldImageService service;

    @PatchMapping
    @Operation(operationId = "updateWorldSettingImage", summary = "대표 이미지 선택·해제", description = "이미지 선택만 변경하며 설정 내용·설정 version·분석 상태는 변경하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이미지 선택 저장 성공"),
            @ApiResponse(responseCode = "400", description = "입력 또는 이미지 분류 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "접근 가능한 작품·대상·이미지가 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미지 선택 버전 충돌", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldSettingImageResponse> updateWorldSettingImage(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @PathVariable UUID worldSettingId,
            @Valid @RequestBody WorldSettingImageUpdateRequest request
    ) {
        return CommonResponse.success(service.updateSettingImage(member.memberId(), workId, worldSettingId, request));
    }
}
