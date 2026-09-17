package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PrivateWorldImageUploadRequest(@NotNull UUID id, @NotNull UUID vaultId,
        @NotBlank @Size(max = 4096) String encryptedMetadata) {}
