package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "AI Worker Job lease 갱신 응답")
public record WorkerAnalysisJobHeartbeatResponse(
        @Schema(description = "현재 lease token")
        UUID leaseToken,

        @Schema(description = "갱신된 lease 만료 시각")
        LocalDateTime leaseExpiresAt
) {
}
