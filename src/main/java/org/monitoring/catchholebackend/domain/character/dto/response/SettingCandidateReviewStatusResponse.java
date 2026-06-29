package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;

@Schema(description = "설정 후보 검토 상태 응답")
public record SettingCandidateReviewStatusResponse(
        @Schema(description = "설정 후보 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333")
        UUID id,

        @Schema(description = "후보 검토 상태", example = "CONFIRMED")
        SettingCandidateReviewStatus reviewStatus
) {
}
