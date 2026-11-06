package org.monitoring.catchholebackend.domain.analysis.service;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@DisplayName("#387 자동 누적 생성의 미래 출처 scalar 조회 — H2")
class AnalysisFutureSourceCheckIntegrationTest extends AnalysisFutureSourceCheckScenarios {
}
