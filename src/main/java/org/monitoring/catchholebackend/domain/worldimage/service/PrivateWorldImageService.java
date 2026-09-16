package org.monitoring.catchholebackend.domain.worldimage.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.PrivateImageVaultCreateRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.request.PrivateWorldImageUploadRequest;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateImageVaultResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateWorldImageResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.web.multipart.MultipartFile;

public interface PrivateWorldImageService {
    PrivateImageVaultResponse getVault(Long memberId);
    PrivateImageVaultResponse createVault(Long memberId, PrivateImageVaultCreateRequest request);
    PageResponse<PrivateWorldImageResponse> list(Long memberId, UUID workId, int page, int size);
    PrivateWorldImageResponse upload(Long memberId, UUID workId, PrivateWorldImageUploadRequest request,
            MultipartFile image, MultipartFile thumbnail);
    byte[] getCiphertext(Long memberId, UUID workId, UUID imageId, boolean thumbnail);
    void delete(Long memberId, UUID workId, UUID imageId);
}
