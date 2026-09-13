package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;

@Schema(description = "AI Worker 분석 작업 claim 요청")
public record WorkerAnalysisJobClaimRequest(
        @Schema(description = "Worker가 사용할 모델명", example = "gpt-5.6-terra", nullable = true)
        @Size(max = 100, message = "모델명은 100자 이하로 입력해주세요.")
        String modelName,

        @Schema(description = "Worker가 기록할 현재 처리 단계", example = "원문 청킹", nullable = true)
        @Size(max = 100, message = "현재 처리 단계는 100자 이하로 입력해주세요.")
        String currentStep,

        @Schema(description = "Worker가 처리할 분석 작업 유형")
        @NotEmpty(message = "처리할 분석 작업 유형은 하나 이상이어야 합니다.")
        Set<AnalysisJobType> allowedJobTypes,

        @Schema(description = "지원하는 분석 입력 정책. 구 Worker의 생략 요청은 CONFIRMED_ONLY만 허용합니다.", nullable = true)
        Set<AnalysisMode> supportedAnalysisModes,
        @Schema(description = "신규 캐릭터 그룹 비교 Job 지원 여부", nullable = true)
        Boolean supportsCharacterComparisonGroups
) {
    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep, Set<AnalysisJobType> allowedJobTypes) {
        this(modelName, currentStep, allowedJobTypes, null, null);
    }

    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep,
            Set<AnalysisJobType> allowedJobTypes, Set<AnalysisMode> supportedAnalysisModes) {
        this(modelName, currentStep, allowedJobTypes, supportedAnalysisModes, null);
    }

    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep,
            Set<AnalysisJobType> allowedJobTypes, Boolean supportsCharacterComparisonGroups) {
        this(modelName, currentStep, allowedJobTypes, null, supportsCharacterComparisonGroups);
    }

    public Set<AnalysisMode> effectiveSupportedAnalysisModes() {
        return supportedAnalysisModes == null || supportedAnalysisModes.isEmpty()
                ? Set.of(AnalysisMode.CONFIRMED_ONLY) : Set.copyOf(supportedAnalysisModes);
    }
}
