package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingConsolidationStatus;

@Schema(description = "Worker 세계관 설정 비교 완료 요청")
public record WorkerWorldSettingComparisonCompleteRequest(
        UUID targetWorldSettingId,
        String matchedScopeName,
        String matchedPropertyName,

        @NotNull(message = "1차 추출값 정리 상태는 필수입니다.")
        WorldSettingConsolidationStatus consolidationStatus,

        @NotNull(message = "세계관 설정 제안 방식은 필수입니다.")
        WorldSettingOperation suggestedOperation,

        @Size(max = 100, message = "제안 범위명은 100자 이하여야 합니다.")
        @Schema(description = "제안된 선택적 한 단계 범위", nullable = true, example = "1층")
        String proposedScopeName,

        @NotBlank(message = "제안 설정명은 필수입니다.")
        @Size(max = 100, message = "제안 설정명은 100자 이하여야 합니다.")
        String proposedSettingName,

        @NotBlank(message = "제안 설정값은 필수입니다.")
        String proposedValue,

        @NotBlank(message = "비교 이유는 필수입니다.")
        String comparisonReason,

        UUID exactTargetWorldSettingId,

        @Valid
        @NotNull(message = "비교 문맥 version 목록은 필수입니다.")
        @Size(max = 3, message = "상세 비교 대상은 최대 3개입니다.")
        List<ContextVersion> contextVersions,

        Map<String, Object> rawComparisonJson
) {

    public WorkerWorldSettingComparisonCompleteRequest(
            UUID targetWorldSettingId,
            String matchedPropertyName,
            WorldSettingConsolidationStatus consolidationStatus,
            WorldSettingOperation suggestedOperation,
            String proposedSettingName,
            String proposedValue,
            String comparisonReason,
            UUID exactTargetWorldSettingId,
            List<ContextVersion> contextVersions,
            Map<String, Object> rawComparisonJson
    ) {
        this(
                targetWorldSettingId,
                null,
                matchedPropertyName,
                consolidationStatus,
                suggestedOperation,
                null,
                proposedSettingName,
                proposedValue,
                comparisonReason,
                exactTargetWorldSettingId,
                contextVersions,
                rawComparisonJson
        );
    }

    public record ContextVersion(
            @NotNull(message = "비교 대상 ID는 필수입니다.")
            UUID worldSettingId,

            @PositiveOrZero(message = "비교 대상 version은 0 이상이어야 합니다.")
            long version
    ) {
    }
}
