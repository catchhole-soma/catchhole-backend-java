package org.monitoring.catchholebackend.domain.worldimage.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.WorldImageBackfillRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldImageBackfillResponse;
import org.monitoring.catchholebackend.domain.worldimage.service.WorldImageBackfillService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/world-images/backfill")
@Tag(name = "WorldImageBackfill", description = "운영자용 기존 대표 이미지 보정")
@SecurityRequirement(name = "bearerAuth")
public class WorldImageBackfillController {
    private final WorldImageBackfillService service;

    @PostMapping
    @Operation(operationId = "backfillSubjectImages", summary = "기존 이미지 연결 보정", description = "ADMIN 전용. 작품·종류별 최대 500개 미처리 대상을 검사하며 apply=true일 때만 저장합니다. processed=0까지 반복 가능하고 직접 선택은 유지합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "예상 결과 또는 보정 완료"),
        @ApiResponse(responseCode = "400", description = "입력 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "운영자 권한 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "작품 없음", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "활성 작품 아님", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorldImageBackfillResponse> backfillSubjectImages(@Valid @RequestBody WorldImageBackfillRequest request) {
        return CommonResponse.success(service.backfillImages(request));
    }
}
