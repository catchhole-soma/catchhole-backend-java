package org.monitoring.catchholebackend.domain.analysis.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import tools.jackson.databind.ObjectMapper;

@DisplayName("관계 복구 Worker capability의 이전 요청 호환성")
class WorkerRelationshipRecoveryClaimContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("생략과 null 및 false는 복구를 허용하지 않고 명시적인 true만 지원을 광고한다")
    void recoveryCapabilityIsOptInOnWire() {
        for (String field : new String[] {"", ",\"supportsRelationshipRecovery\":null",
                ",\"supportsRelationshipRecovery\":false"}) {
            var request = read(field);
            assertThat(Boolean.TRUE.equals(request.supportsRelationshipRecovery())).isFalse();
            assertThat(request.supportsRelationshipAnalysis()).isTrue();
            assertThat(request.allowedJobTypes()).containsExactly(AnalysisJobType.RELATIONSHIP_EXTRACTION);
        }
        var supported = read(",\"supportsRelationshipRecovery\":true");
        assertThat(supported.supportsRelationshipRecovery()).isTrue();
        assertThat(json.readTree(json.writeValueAsString(supported))
                .path("supportsRelationshipRecovery").booleanValue()).isTrue();
    }

    @Test
    @DisplayName("이전 10인자와 최소 생성자는 관계 분석 지원을 복구 지원으로 승격하지 않는다")
    void legacyConstructorsDoNotAdvertiseRecovery() {
        var previous = new WorkerAnalysisJobClaimRequest("gpt-6.1-terra", null,
                Set.of(AnalysisJobType.RELATIONSHIP_EXTRACTION), Set.of(AnalysisMode.CONFIRMED_ONLY),
                false, Set.of(AnalysisSourceKind.EPISODE), false, false, false, true);
        assertThat(previous.supportsRelationshipAnalysis()).isTrue();
        assertThat(previous.supportsRelationshipRecovery()).isNull();
        var oldest = new WorkerAnalysisJobClaimRequest(null, null,
                Set.of(AnalysisJobType.SETTING_EXTRACTION));
        assertThat(oldest.supportsRelationshipRecovery()).isNull();
    }

    private WorkerAnalysisJobClaimRequest read(String field) {
        return json.readValue("{\"allowedJobTypes\":[\"RELATIONSHIP_EXTRACTION\"],"
                + "\"supportsRelationshipAnalysis\":true" + field + "}",
                WorkerAnalysisJobClaimRequest.class);
    }
}
