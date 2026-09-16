package org.monitoring.catchholebackend.domain.worldimage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import io.swagger.v3.oas.annotations.Parameter;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageCatalogResponse;
import org.monitoring.catchholebackend.domain.worldimage.service.WorldImageService;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/world-image-catalog")
@Tag(name = "WorldImageCatalog", description = "세계관 대표 이미지 도감")
@SecurityRequirement(name = "bearerAuth")
public class WorldImageCatalogController {
    private final WorldImageService service;

    @GetMapping
    @Operation(operationId = "getWorldImageCatalog", summary = "분류별 대표 이미지 이름·별칭 검색")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "도감 조회 성공"),
            @ApiResponse(responseCode = "400", description = "검색 조건 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<PageResponse<WorldImageCatalogResponse>> getWorldImageCatalog(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Parameter(description = "장르 추천을 받을 작품 ID") @RequestParam(required = false) UUID workId,
            @Parameter(description = "true는 작품 장르 추천, false는 같은 분류 전체 도감") @RequestParam(defaultValue = "false") boolean recommended,
            @RequestParam WorldSettingCategory category,
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "18") @Min(1) @Max(60) int size
    ) {
        return CommonResponse.success(service.getImageCatalog(member.memberId(), workId, recommended, category, q, page, size));
    }
}
