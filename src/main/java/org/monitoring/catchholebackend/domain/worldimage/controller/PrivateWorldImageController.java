package org.monitoring.catchholebackend.domain.worldimage.controller;

import org.springframework.validation.annotation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.PrivateWorldImageUploadRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateWorldImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.service.PrivateWorldImageService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/private-world-images")
@Tag(name = "PrivateWorldImage", description = "작품 소유자만 조회·선택하는 암호화 이미지. 원본과 썸네일 모두 암호문으로 저장합니다.")
@SecurityRequirement(name = "bearerAuth")
public class PrivateWorldImageController {
    private final PrivateWorldImageService service;

    @GetMapping
    @Operation(operationId = "getPrivateWorldImages", summary = "작품의 내 이미지 목록", description = "작품 소유자에게 저장이 완료된 개인 이미지만 페이지 단위로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개인 이미지 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "페이지 조건 오류", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품이 없거나 소유자가 아님", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<PageResponse<PrivateWorldImageResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "18") @Min(1) @Max(50) int size) {
        return CommonResponse.success(service.list(member.memberId(), workId, page, size));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadPrivateWorldImage", summary = "암호화한 개인 이미지 업로드", description = "image·thumbnail은 CHI1 인증 암호문입니다. 이미지 내용이나 복구키를 전송하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개인 이미지 저장 완료"),
            @ApiResponse(responseCode = "400", description = "암호문·메타데이터 오류, 보관함 없음 또는 이미지 개수 초과", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품이 없거나 소유자가 아님", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "이미지 중복 또는 작품·이미지 상태 충돌", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "이미지 저장소 처리 실패", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<PrivateWorldImageResponse> upload(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @Valid @RequestPart("metadata") PrivateWorldImageUploadRequest request,
            @RequestPart("image") MultipartFile image, @RequestPart("thumbnail") MultipartFile thumbnail) {
        return CommonResponse.success(service.upload(member.memberId(), workId, request, image, thumbnail));
    }

    @GetMapping(value = "/{imageId}/image", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "getPrivateWorldImageContent", summary = "개인 이미지 암호문 조회", description = "작품 소유자에게 저장이 완료된 이미지 암호문을 캐시 금지로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개인 이미지 암호문 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "잘못된 식별자", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품·이미지가 없거나 소유자가 아님", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "이미지 저장소 조회 실패", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public ResponseEntity<byte[]> image(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID imageId) {
        return ciphertext(service.getCiphertext(member.memberId(), workId, imageId, false));
    }

    @GetMapping(value = "/{imageId}/thumbnail", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "getPrivateWorldImageThumbnail", summary = "개인 이미지 썸네일 암호문 조회", description = "작품 소유자에게 썸네일 암호문을 캐시 금지로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개인 이미지 썸네일 암호문 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "잘못된 식별자", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품·이미지가 없거나 소유자가 아님", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "이미지 저장소 조회 실패", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public ResponseEntity<byte[]> thumbnail(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID imageId) {
        return ciphertext(service.getCiphertext(member.memberId(), workId, imageId, true));
    }

    @DeleteMapping("/{imageId}")
    @Operation(operationId = "deletePrivateWorldImage", summary = "사용하지 않는 개인 이미지 삭제", description = "세계관·캐릭터에서 사용 중인 이미지는 삭제할 수 없습니다. 저장소 삭제 실패는 숨겨진 삭제 대기로 남아 재시도합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개인 이미지 삭제 완료"),
            @ApiResponse(responseCode = "400", description = "잘못된 식별자", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "작품·이미지가 없거나 소유자가 아님", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "사용 중이거나 업로드·작품 파기 진행 중", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "이미지 삭제 실패, 정리 재시도 예정", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public CommonResponse<Void> delete(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID imageId) {
        service.delete(member.memberId(), workId, imageId);
        return CommonResponse.success(null);
    }

    private ResponseEntity<byte[]> ciphertext(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
