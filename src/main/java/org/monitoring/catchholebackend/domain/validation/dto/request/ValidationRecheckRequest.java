package org.monitoring.catchholebackend.domain.validation.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record ValidationRecheckRequest(
        @NotBlank @Size(max=100) String idempotencyKey,
        @NotNull @PositiveOrZero Long expectedReportVersion,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String expectedContentHash,
        @NotBlank @Size(max=512) String expectedContentS3Key,
        @Size(max=255) String expectedContentS3Version,
        @NotNull @PositiveOrZero Long expectedContentRevision,
        @Pattern(regexp="[0-9a-f]{64}") String expectedBaselineHash,
        UUID correctionReceiptId) {}
