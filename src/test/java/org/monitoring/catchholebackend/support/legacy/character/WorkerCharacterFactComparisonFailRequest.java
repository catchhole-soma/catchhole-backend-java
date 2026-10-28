// Exact pre-GH304 DTO from 4b68365aff7ca58265f43bab0c7f08a276765371; only the test package differs.
package org.monitoring.catchholebackend.support.legacy.character;

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
