package org.monitoring.catchholebackend.domain.worldimage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record PrivateImageVaultCreateRequest(@NotNull UUID id,
        @NotBlank @Size(max = 512) String keyCheck) {}
