package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record WorkerWorldSettingComparisonBatchContextRequest(
        @NotNull(message = "비교 대상 목록은 필수입니다.")
        @Size(max = 20, message = "묶음 상세 비교 대상은 최대 20개입니다.")
        List<UUID> targetWorldSettingIds
) {
}
