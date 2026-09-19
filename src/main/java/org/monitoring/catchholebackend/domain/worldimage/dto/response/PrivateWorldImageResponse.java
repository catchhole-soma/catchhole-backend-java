package org.monitoring.catchholebackend.domain.worldimage.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record PrivateWorldImageResponse(UUID id, UUID workId, UUID vaultId,
        String encryptedMetadata, LocalDateTime createdAt, String name) {}
