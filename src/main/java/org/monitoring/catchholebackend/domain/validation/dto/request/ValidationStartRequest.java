package org.monitoring.catchholebackend.domain.validation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

@Schema(description = "저장한 신규 단일 회차의 검수 접수. 원문 전문이나 반영 정책을 클라이언트에서 받지 않습니다.")
public record ValidationStartRequest(
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotNull UUID batchId,
        @NotNull UUID episodeId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedContentHash,
        @NotBlank @Size(max = 512) String expectedContentS3Key,
        @Size(max = 255) String expectedContentS3Version) {}
