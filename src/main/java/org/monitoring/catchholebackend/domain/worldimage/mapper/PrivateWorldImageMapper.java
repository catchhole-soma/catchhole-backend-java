package org.monitoring.catchholebackend.domain.worldimage.mapper;

import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateImageVaultResponse;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.PrivateWorldImageResponse;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateImageVault;
import org.monitoring.catchholebackend.domain.worldimage.entity.PrivateWorldImage;
import org.springframework.stereotype.Component;

@Component
public class PrivateWorldImageMapper {
    public PrivateImageVaultResponse toResponse(PrivateImageVault vault) {
        return new PrivateImageVaultResponse(vault.getId(), vault.getKeyCheck());
    }
    public PrivateWorldImageResponse toResponse(PrivateWorldImage image) {
        return new PrivateWorldImageResponse(image.getId(), image.getWork().getId(),
                image.getVault() == null ? null : image.getVault().getId(),
                image.getEncryptedMetadata(), image.getCreatedAt(), image.getDisplayName());
    }
}
