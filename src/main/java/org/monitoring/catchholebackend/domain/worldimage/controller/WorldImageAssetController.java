package org.monitoring.catchholebackend.domain.worldimage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.worldimage.service.WorldImageService;
import org.monitoring.catchholebackend.global.common.response.CommonErrorResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/world-image-assets")
@Tag(name = "WorldImageAsset", description = "도감에 등록된 공용 WebP 파일만 제공. 원고·작품 파일에는 접근하지 않습니다.")
public class WorldImageAssetController {
    private final WorldImageService service;

    @GetMapping(value = "/{sha:[a-f0-9]{64}}.webp", produces = "image/webp")
    @Operation(operationId = "getWorldImageAsset", summary = "공용 대표 이미지 파일 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "WebP 이미지"),
            @ApiResponse(responseCode = "404", description = "등록되지 않은 이미지", content = @Content(schema = @Schema(implementation = CommonErrorResponse.class)))
    })
    public ResponseEntity<byte[]> getWorldImageAsset(@PathVariable String sha) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/webp"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag(sha).body(service.getPublishedImage(sha));
    }
}
