package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;

@Schema(description = "세계관 대상 목록 항목")
public record WorldSettingListItemResponse(
        UUID id,
        WorldSettingCategory category,
        String subjectName,
        int propertyCount,
        long version,
        LocalDateTime updatedAt,
        @Schema(nullable = true) String matchedSettingName,
        @Schema(nullable = true) String matchedSettingValue
) {
}
