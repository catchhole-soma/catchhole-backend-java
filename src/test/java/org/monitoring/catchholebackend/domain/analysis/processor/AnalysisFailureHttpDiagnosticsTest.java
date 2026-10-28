package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver.InvalidEvidenceException;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.exception.GlobalExceptionHandler;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("Worker HTTP의 안전한 실패 진단")
class AnalysisFailureHttpDiagnosticsTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); MDC.clear(); }

    @Test @DisplayName("검증 409·저장소 503·미상 500의 단계와 실제 응답 상태를 구분한다")
    void mapsKnownAndUnknownErrorsWithoutExceptionProse() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST",
                "/api/internal/v1/analysis-jobs/synthetic/character-fact-comparison-context")));
        MDC.put("requestId", "11111111-1111-4111-8111-111111111111");
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        var handler = new GlobalExceptionHandler();
        var invalid = handler.handleAppException(new InvalidEvidenceException("CURRENT_EVIDENCE_OFFSET_INVALID"));
        var source = handler.handleAppException(new AppException(AnalysisJobErrorCode.ANALYSIS_CURRENT_EVIDENCE_SOURCE_UNAVAILABLE));
        var unknown = handler.handleException(new IllegalStateException("PRIVATE_MANUSCRIPT"));
        assertThat(invalid.getStatusCode().value()).isEqualTo(409);
        assertThat(source.getStatusCode().value()).isEqualTo(503);
        assertThat(unknown.getStatusCode().value()).isEqualTo(500);
        var diagnostic = (AnalysisFailureDiagnostics) unknown.getBody().error().context().get("failureDiagnostics");
        assertThat(diagnostic.reasonCode()).isEqualTo("UNKNOWN");
        assertThat(diagnostic.stage()).isEqualTo("CHARACTER_CONTEXT");
        assertThat(diagnostic.httpStatus()).isEqualTo(500);
        assertThat(diagnostic.requestId()).isEqualTo(MDC.get("requestId"));
        assertThat(diagnostic.traceId()).isEqualTo(MDC.get("traceId"));
        assertThat(unknown.getBody().toString()).doesNotContain("PRIVATE_MANUSCRIPT");
    }
}
