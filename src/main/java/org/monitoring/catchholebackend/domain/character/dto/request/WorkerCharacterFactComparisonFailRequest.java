package org.monitoring.catchholebackend.domain.character.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;

public record WorkerCharacterFactComparisonFailRequest(
        @NotNull
        AnalysisFailureCode failureCode,

        @NotBlank(message = "비교 실패 메시지는 필수입니다.")
        @Size(max = 1000)
        String errorMessage
) {
}
