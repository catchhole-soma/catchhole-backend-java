package org.monitoring.catchholebackend.domain.work.controller;

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
import org.monitoring.catchholebackend.domain.work.dto.request.WorkCoverUpdateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkResponse;
import org.monitoring.catchholebackend.domain.work.service.WorkCoverService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/cover")
@Tag(name = "WorkCover", description = "작품 소유자 전용 표지 이미지 저장·조회 API")
@SecurityRequirement(name = "bearerAuth")
public class WorkCoverController {
    private final WorkCoverService service;

    @PatchMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateWorkCover", summary = "작품 정보와 표지 저장",
            description = "metadata JSON과 선택한 image 파일로 작품 정보와 표지를 함께 저장합니다. REPLACE는 JPG·PNG·WebP 5MB, 각 변 4,096px 이하이며 실제 디코딩 후 PNG로 저장합니다. REMOVE는 파일 없이 기본 표지로 복귀합니다. 실패 시 기존 표지를 보존하며 AI 분석을 실행하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "작품 정보·표지 저장 성공"),
        @ApiResponse(responseCode = "400", description = "파일 형식·크기·해상도 또는 작품 정보 오류", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "로그인 필요", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "작품이 없거나 소유자가 아님", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "표지 버전 충돌·저장 중 또는 작품 삭제 중", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "저장 실패, 기존 표지 유지", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<WorkResponse> updateWorkCover(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId,
            @Valid @RequestPart("metadata") WorkCoverUpdateRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return CommonResponse.success("작품이 수정되었습니다.", service.updateWorkCover(member.memberId(), workId, request, image));
    }

    @GetMapping(value = "/{imageId}", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(operationId = "getWorkCover", summary = "현재 작품 표지 조회",
            description = "현재 선택된 표지만 작품 소유자에게 반환합니다. 교체·삭제된 표지는 조회할 수 없으며 브라우저 공유 캐시를 금지합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "표지 PNG", content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "401", description = "로그인 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "작품·표지가 없거나 소유자가 아님", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "작품 삭제 중", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "저장소 조회 실패", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public ResponseEntity<byte[]> getWorkCover(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID imageId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.getWorkCover(member.memberId(), workId, imageId));
    }
}
