package org.monitoring.catchholebackend.domain.character.dto.request;

import jakarta.validation.constraints.NotBlank;

public record WorkerCharacterFactComparisonFailRequest(
        @NotBlank(message = "비교 실패 메시지는 필수입니다.")
        String errorMessage
) {
}
