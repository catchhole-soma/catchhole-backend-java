package org.monitoring.catchholebackend.domain.character.dto.request;

import jakarta.validation.constraints.NotBlank;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;

public record WorkerCharacterFactComparisonFailRequest(
        AnalysisFailureCode failureCode,

        @NotBlank(message = "비교 실패 메시지는 필수입니다.")
        String errorMessage
) {
}
