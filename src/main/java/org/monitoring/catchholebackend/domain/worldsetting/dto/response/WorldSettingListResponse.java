package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@Schema(description = "작품별 세계관 대상 목록")
public record WorldSettingListResponse(
        long totalWorldSettingCount,
        PageResponse<WorldSettingListItemResponse> worldSettings
) {
}
