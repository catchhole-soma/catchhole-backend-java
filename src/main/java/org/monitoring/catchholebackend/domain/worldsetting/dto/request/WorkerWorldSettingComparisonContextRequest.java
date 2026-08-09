package org.monitoring.catchholebackend.domain.worldsetting.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "Worker 세계관 설정 상세 비교 문맥 요청")
public record WorkerWorldSettingComparisonContextRequest(
        @NotNull(message = "비교 대상 ID 목록은 필수입니다.")
        @Size(max = 3, message = "상세 비교 대상은 최대 3개입니다.")
        List<UUID> targetWorldSettingIds
) {
}
