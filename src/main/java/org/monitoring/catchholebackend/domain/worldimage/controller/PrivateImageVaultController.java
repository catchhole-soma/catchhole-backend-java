package org.monitoring.catchholebackend.domain.worldimage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.auth.security.MemberPrincipal;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.PrivateImageVaultCreateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateImageVaultResponse;
import org.monitoring.catchholebackend.domain.worldimage.service.PrivateWorldImageService;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/private-image-vault")
@Tag(name = "PrivateImageVault", description = "작가 기기에서만 복호화하는 개인 이미지 보관함. 키는 서버에 전송하지 않습니다.")
@SecurityRequirement(name = "bearerAuth")
public class PrivateImageVaultController {
    private final PrivateWorldImageService service;

    @GetMapping
    @Operation(operationId = "getPrivateImageVault", summary = "내 이미지 보관함 확인", description = "아직 만들지 않았다면 data=null입니다.")
    public CommonResponse<PrivateImageVaultResponse> get(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return CommonResponse.success(service.getVault(member.memberId()));
    }

    @PostMapping
    @Operation(operationId = "createPrivateImageVault", summary = "내 이미지 보관함 생성", description = "브라우저가 생성한 검증 암호문만 받습니다. 키 교체·서버 키 복구는 제공하지 않습니다.")
    public CommonResponse<PrivateImageVaultResponse> create(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member,
            @Valid @RequestBody PrivateImageVaultCreateRequest request) {
        return CommonResponse.success(service.createVault(member.memberId(), request));
    }
}
