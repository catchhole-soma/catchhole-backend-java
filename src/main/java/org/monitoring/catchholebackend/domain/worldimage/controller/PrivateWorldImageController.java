package org.monitoring.catchholebackend.domain.worldimage.controller;

import io.swagger.v3.oas.annotations.Operation;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/works/{workId}/private-world-images")
@Tag(name = "PrivateWorldImage", description = "작품 소유자만 조회·선택하는 암호화 이미지. 원본과 썸네일 모두 암호문으로 저장합니다.")
@SecurityRequirement(name = "bearerAuth")
public class PrivateWorldImageController {
    private final PrivateWorldImageService service;

    @GetMapping
    @Operation(operationId = "getPrivateWorldImages", summary = "작품의 내 이미지 목록")
    public CommonResponse<PageResponse<PrivateWorldImageResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "18") @Min(1) @Max(50) int size) {
        return CommonResponse.success(service.list(member.memberId(), workId, page, size));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadPrivateWorldImage", summary = "암호화한 개인 이미지 업로드", description = "image·thumbnail은 CHI1 인증 암호문입니다. 이미지 내용이나 복구키를 전송하지 않습니다.")
    public CommonResponse<PrivateWorldImageResponse> upload(
            @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @PathVariable UUID workId,
            @Valid @RequestPart("metadata") PrivateWorldImageUploadRequest request,
            @RequestPart("image") MultipartFile image, @RequestPart("thumbnail") MultipartFile thumbnail) {
        return CommonResponse.success(service.upload(member.memberId(), workId, request, image, thumbnail));
    }

    @GetMapping(value = "/{imageId}/image", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "getPrivateWorldImageContent", summary = "개인 이미지 암호문 조회")
    public ResponseEntity<byte[]> image(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID imageId) {
        return ciphertext(service.getCiphertext(member.memberId(), workId, imageId, false));
    }

    @GetMapping(value = "/{imageId}/thumbnail", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "getPrivateWorldImageThumbnail", summary = "개인 이미지 썸네일 암호문 조회")
    public ResponseEntity<byte[]> thumbnail(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @PathVariable UUID workId, @PathVariable UUID imageId) {
        return ciphertext(service.getCiphertext(member.memberId(), workId, imageId, true));
    }

    @DeleteMapping("/{imageId}")
    @Operation(operationId = "deletePrivateWorldImage", summary = "사용하지 않는 개인 이미지 삭제")
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
