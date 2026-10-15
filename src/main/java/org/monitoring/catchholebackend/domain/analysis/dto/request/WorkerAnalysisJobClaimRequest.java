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
        Boolean supportsCharacterComparisonGroups,
        @Schema(description = "지원 원문 종류. 생략하면 EPISODE만 처리합니다.", nullable = true)
        Set<org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind> supportedSourceKinds,
        @Schema(description = "설정집 불변 기준 시점 해석 지원 여부. 생략하면 설정집을 claim하지 않습니다.", nullable = true)
        Boolean supportsSettingBookReferenceTime,
        @Schema(description = "claim 전용 전체 고정 CURRENT 문맥 지원. true인 누적 모드에만 전달합니다.", nullable = true)
        Boolean supportsSemanticCurrentContext,
        @Schema(description = "고정 원문 근거와 같은 인물의 전체 CURRENT 비교 문맥 지원. true인 Worker만 누적 작업을 claim합니다.", nullable = true)
        Boolean supportsFrozenComparisonEvidence
) {
    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep, Set<AnalysisJobType> allowedJobTypes,
            Set<AnalysisMode> supportedAnalysisModes, Boolean supportsCharacterComparisonGroups,
            Set<org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind> supportedSourceKinds,
            Boolean supportsSettingBookReferenceTime, Boolean supportsSemanticCurrentContext) {
        this(modelName, currentStep, allowedJobTypes, supportedAnalysisModes, supportsCharacterComparisonGroups,
                supportedSourceKinds, supportsSettingBookReferenceTime, supportsSemanticCurrentContext, null);
    }
    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep, Set<AnalysisJobType> allowedJobTypes,
            Set<AnalysisMode> supportedAnalysisModes, Boolean supportsCharacterComparisonGroups,
            Set<org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind> supportedSourceKinds,
            Boolean supportsSettingBookReferenceTime) {
        this(modelName, currentStep, allowedJobTypes, supportedAnalysisModes, supportsCharacterComparisonGroups,
                supportedSourceKinds, supportsSettingBookReferenceTime, null);
    }
    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep, Set<AnalysisJobType> allowedJobTypes) {
        this(modelName, currentStep, allowedJobTypes, null, null, null, null);
    }

    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep,
            Set<AnalysisJobType> allowedJobTypes, Set<AnalysisMode> supportedAnalysisModes) {
        this(modelName, currentStep, allowedJobTypes, supportedAnalysisModes, null, null, null);
    }

    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep,
            Set<AnalysisJobType> allowedJobTypes, Boolean supportsCharacterComparisonGroups) {
        this(modelName, currentStep, allowedJobTypes, null, supportsCharacterComparisonGroups, null, null);
    }

    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep, Set<AnalysisJobType> allowedJobTypes,
            Set<AnalysisMode> supportedAnalysisModes, Boolean supportsCharacterComparisonGroups) {
        this(modelName, currentStep, allowedJobTypes, supportedAnalysisModes, supportsCharacterComparisonGroups, null, null);
    }
    public WorkerAnalysisJobClaimRequest(String modelName, String currentStep, Set<AnalysisJobType> allowedJobTypes,
            Set<AnalysisMode> supportedAnalysisModes, Boolean supportsCharacterComparisonGroups,
            Set<org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind> supportedSourceKinds) {
        this(modelName, currentStep, allowedJobTypes, supportedAnalysisModes, supportsCharacterComparisonGroups, supportedSourceKinds, null);
    }
    public Set<org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind> effectiveSupportedSourceKinds() {
        if (supportedSourceKinds == null || supportedSourceKinds.isEmpty()) {
            return Set.of(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE);
        }
        java.util.EnumSet<org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind> kinds =
                java.util.EnumSet.copyOf(supportedSourceKinds);
        if (!Boolean.TRUE.equals(supportsSettingBookReferenceTime)) {
            kinds.remove(org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK);
        }
        return Set.copyOf(kinds);
    }
    public Set<AnalysisMode> effectiveSupportedAnalysisModes() {
        java.util.EnumSet<AnalysisMode> modes = supportedAnalysisModes == null || supportedAnalysisModes.isEmpty()
                ? java.util.EnumSet.of(AnalysisMode.CONFIRMED_ONLY) : java.util.EnumSet.copyOf(supportedAnalysisModes);
        if (!Boolean.TRUE.equals(supportsFrozenComparisonEvidence)) modes.remove(AnalysisMode.ORDERED_PROVISIONAL);
        return Set.copyOf(modes);
    }
}
