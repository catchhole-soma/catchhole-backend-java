package org.monitoring.catchholebackend.domain.validation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.dto.request.CharacterUpdateRequest;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingPropertyUpdateRequest;

/** 전체 편집 command는 재사용하되 명시한 현재 slot/path 하나만 정정할 수 있다. */
public record ValidationCorrectionRequest(
        @NotBlank @Size(max=100) String idempotencyKey,
        @NotNull @PositiveOrZero Long expectedReportVersion,
        @NotNull UUID baselineId,
        @NotNull @Positive Long expectedBaselineRevision,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String expectedBaselineHash,
        @NotBlank @Size(max=30) @Pattern(regexp="B[1-9][0-9]*") String entryRef,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String expectedEntryHash,
        @Valid CharacterCorrection character,
        @Valid WorldSettingCorrection worldSetting) {
    public record CharacterCorrection(@NotNull UUID targetId, @NotNull @PositiveOrZero Long expectedSnapshotVersion,
            @NotNull CharacterFactType factType, @NotBlank @Size(max=150) String factKey, @NotNull @Valid CharacterUpdateRequest edit) {}
    public record WorldSettingCorrection(@NotNull UUID targetId, @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid WorldSettingPropertyUpdateRequest edit) {}
}
