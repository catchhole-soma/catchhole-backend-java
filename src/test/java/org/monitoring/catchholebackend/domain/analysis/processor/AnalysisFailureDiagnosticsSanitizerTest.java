package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("실패 진단의 비신뢰 입력 정리")
class AnalysisFailureDiagnosticsSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisFailureDiagnosticsSanitizer sanitizer = new AnalysisFailureDiagnosticsSanitizer(mock(JdbcTemplate.class));
    private final UUID workId = UUID.randomUUID();

    @Test @DisplayName("합성 공통 계약의 안전한 필드를 보존하고 미검증 식별자를 제거한다")
    void foreignSourceIdentifiersAreRemovedWithoutRejectingFailure() throws Exception {
        var input = mapper.readTree(getClass().getResourceAsStream("/contracts/analysis-failure-diagnostics-v1.json"));
        var result = sanitizer.sanitize(input, workId);
        assertThat(result.reasonCode()).isEqualTo("CURRENT_EVIDENCE_OFFSET_INVALID");
        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.sourceCandidateId()).isNull();
        assertThat(result.sourceAnalysisJobId()).isNull();
        assertThat(result.sourceEpisodeId()).isNull();
    }

    @Test @DisplayName("잘못된 선택 진단과 미지원 버전은 예외 없이 버린다")
    void malformedOptionalDiagnosticsStillRecordsFailure() throws Exception {
        assertThat(sanitizer.sanitize(mapper.readTree("[1]"), workId)).isNull();
        assertThat(sanitizer.sanitize(null, workId)).isNull();
        assertThat(sanitizer.sanitize(mapper.readTree("{\"schemaVersion\":2}"), workId)).isNull();
        assertThat(sanitizer.sanitize(mapper.readTree("{\"schemaVersion\":1}"), workId)).isNull();
        assertThat(sanitizer.sanitize(mapper.readTree("{\"schemaVersion\":1,\"origin\":{}}"), workId)).isNull();
        assertThat(sanitizer.sanitize(mapper.readTree("{\"schemaVersion\":1,\"origin\":[]}"), workId)).isNull();
        assertThat(sanitizer.sanitize(mapper.readTree("{\"schemaVersion\":4294967297,\"origin\":\"SPRING\"}"), workId)).isNull();
    }

    @Test @DisplayName("미지원 코드와 자유 텍스트를 저장하지 않는다")
    void unsupportedAndUnsafeFieldsAreDropped() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/contracts/analysis-failure-diagnostics-v1.json"));
        input.put("reasonCode", "PRIVATE_MANUSCRIPT").put("sourceErrorCode", "PRIVATE_CODE")
             .put("requestId", "@everyone https://private.example").put("traceId", "x".repeat(1000))
             .put("leaseToken", "PRIVATE_TOKEN");
        var result = sanitizer.sanitize(input, workId);
        assertThat(result.reasonCode()).isEqualTo("UNKNOWN");
        assertThat(result.sourceErrorCode()).isEqualTo("UNKNOWN");
        assertThat(result.requestId()).isNull();
        assertThat(result.traceId()).isNull();
        assertThat(mapper.writeValueAsString(result)).doesNotContain("PRIVATE", "https://", "@everyone", "leaseToken");
    }
    @Test @DisplayName("선택 진단은 세계관 완료의 기존 멱등 기준을 바꾸지 않는다")
    void optionalDiagnosticsDoNotChangeBusinessCompletionFingerprint() throws Exception {
        var mapper = new org.monitoring.catchholebackend.domain.worldsetting.mapper.WorldSettingWorkerMapper();
        var failure = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest.Failure(
                java.util.List.of("C1"), org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode.LLM_PROVIDER_ERROR,
                "합성 실패", java.util.List.of(), this.mapper.readTree("{\"private\":\"PRIVATE_MANUSCRIPT\"}"));
        var request = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest(
                java.util.List.of(), java.util.List.of(), java.util.Map.of(), null, java.util.List.of(failure));
        var legacyFailure = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest.Failure(
                failure.sourceCandidateRefs(), failure.failureCode(), failure.errorMessage(), java.util.List.of());
        var legacyRequest = new org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorkerWorldSettingComparisonBatchCompleteRequest(
                java.util.List.of(), java.util.List.of(), java.util.Map.of(), null, java.util.List.of(legacyFailure));
        assertThat(mapper.toCompletionFingerprintPayload(request)).isEqualTo(mapper.toCompletionFingerprintPayload(legacyRequest));
        assertThat(mapper.toCompletionFingerprintPayload(request).toString()).doesNotContain("failureDiagnostics", "PRIVATE_MANUSCRIPT");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"schemaVersion", "origin", "stage", "reasonCode", "sourceErrorCode",
            "httpStatus", "requestId", "traceId", "sourceAnalysisJobId", "sourceCandidateId", "sourceEpisodeId", "sourceEpisodeNo"})
    @DisplayName("모든 스칼라 필드의 구조·누락·타입 오류는 선택 진단만 버린다")
    void malformedScalarShapesNeverRejectFailure(String field) throws Exception {
        for (String malformed : java.util.List.of("{}", "[]", "null", "true", "\"PRIVATE_VALUE\"")) {
            ObjectNode input = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/contracts/analysis-failure-diagnostics-v1.json"));
            input.set(field, mapper.readTree(malformed));
            assertMalformedFieldDiscarded(input, field);
        }
        ObjectNode missing = (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/contracts/analysis-failure-diagnostics-v1.json"));
        missing.remove(field);
        assertMalformedFieldDiscarded(missing, field);
    }

    private void assertMalformedFieldDiscarded(ObjectNode input, String field) {
        var result = sanitizer.sanitize(input, workId);
        if (field.equals("schemaVersion") || field.equals("origin")) {
            assertThat(result).isNull();
        } else {
            assertThat(result).isNotNull();
            var value = mapper.valueToTree(result).path(field);
            if (java.util.Set.of("stage", "reasonCode", "sourceErrorCode").contains(field)) {
                assertThat(value.stringValue()).isEqualTo("UNKNOWN");
            } else {
                assertThat(value.isNull()).isTrue();
            }
        }
    }

}
