package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PrivateWorldImageUploadRequest(@NotNull UUID id, UUID vaultId,
        @Size(max = 4096) String encryptedMetadata, @Size(max = 180) String name) {
    public PrivateWorldImageUploadRequest(UUID id, UUID vaultId, String encryptedMetadata) {
        this(id, vaultId, encryptedMetadata, null);
    }
}
