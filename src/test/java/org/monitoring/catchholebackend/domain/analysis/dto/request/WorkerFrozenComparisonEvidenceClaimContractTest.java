package org.monitoring.catchholebackend.domain.analysis.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;

@DisplayName("고정 비교 근거를 소비하지 못하는 Worker의 ORDERED claim은 계약으로 차단한다")
class WorkerFrozenComparisonEvidenceClaimContractTest {
    @Test @DisplayName("새 capability 없는 ORDERED-only Worker는 처리할 mode가 없다")
    void missingCapabilityCannotClaimOrderedInput() {
        var request = new WorkerAnalysisJobClaimRequest(null,null,Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(AnalysisMode.ORDERED_PROVISIONAL),true,Set.of(AnalysisSourceKind.EPISODE),true,true);
        assertThat(request.effectiveSupportedAnalysisModes()).isEmpty();
    }
    @Test @DisplayName("구 혼합 Worker는 기존 CONFIRMED_ONLY만 계속 처리한다")
    void legacyMixedWorkerKeepsOnlyConfirmedMode() {
        var request = new WorkerAnalysisJobClaimRequest(null,null,Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(AnalysisMode.ORDERED_PROVISIONAL,AnalysisMode.CONFIRMED_ONLY),true,Set.of(AnalysisSourceKind.EPISODE),true,true);
        assertThat(request.effectiveSupportedAnalysisModes()).containsExactly(AnalysisMode.CONFIRMED_ONLY);
    }
}
