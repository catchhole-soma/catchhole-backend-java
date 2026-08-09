package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "세계관 후보 대상 그룹 확정 또는 제외 결과")
public record WorldSettingCandidateGroupActionResponse(
        String groupKey,
        @Schema(nullable = true) UUID worldSettingId,
        @Schema(nullable = true) Long appliedWorldSettingVersion,
        List<WorldSettingCandidateResponse> candidates
) {
}
