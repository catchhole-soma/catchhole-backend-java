package org.monitoring.catchholebackend.global.config.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = "management.health.redis.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RequestLoggingIntegrationTest.ProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("HTTP requestId와 실제 보안 체인 통합")
class RequestLoggingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("응답과 로그를 같은 서버 UUID로 연결하고 요청별로 정리한다")
    void correlatesResponseAndLogs(CapturedOutput output) throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/legal-documents/log-probe/first")
                        .header("X-Request-ID", "client-supplied-id")
                        .header("Origin", "http://localhost:3000")
                        .queryParam("token", "do-not-log-this"))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/legal-documents/log-probe/second"))
                .andExpect(status().isOk()).andReturn();

        String firstId = requestId(first);
        String secondId = requestId(second);
        assertThat(firstId).isNotEqualTo(secondId).isNotEqualTo("client-supplied-id");
        assertThat(first.getResponse().getContentAsString()).contains(firstId);
        assertThat(first.getResponse().getHeader("Access-Control-Expose-Headers")).contains("X-Request-ID");
        assertThat(output.getOut()).contains("[requestId=" + firstId + "]", "[requestId=" + secondId + "]")
                .contains("path=/api/v1/legal-documents/log-probe/{probeId}", "status=200")
                .doesNotContain("do-not-log-this", "client-supplied-id");
        assertThat(output.getOut().lines().filter(line ->
                line.contains("[requestId=" + firstId + "]") && line.contains("HTTP 요청 완료")).count())
                .isEqualTo(1);
        assertThat(MDC.get("requestId")).isNull();
        LoggerFactory.getLogger(getClass()).info("HTTP 요청 밖 로그 검증");
        assertThat(output.getOut()).contains("[requestId=-]");
    }

    @Test
    @DisplayName("공개·내부 API 인증 실패와 관리자 권한 거절에도 ID를 반환한다")
    void coversBothSecurityChains() throws Exception {
        requestId(mockMvc.perform(get("/api/v1/works")).andExpect(status().isUnauthorized()).andReturn());
        requestId(mockMvc.perform(get("/api/internal/log-probe"))
                .andExpect(status().isUnauthorized()).andReturn());
        MvcResult internal = mockMvc.perform(get("/api/internal/log-probe")
                        .header("X-Internal-Api-Key", "local-development-internal-api-key"))
                .andExpect(status().isOk()).andReturn();
        assertThat(internal.getResponse().getContentAsString()).contains(requestId(internal));
        requestId(mockMvc.perform(get("/api/v1/admin/world-images/log-probe").with(user("tester").roles("MEMBER")))
                .andExpect(status().isForbidden()).andReturn());
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("입력 오류와 예상하지 못한 예외에도 헤더를 유지하고 500 원인을 기록한다")
    void coversValidationAndServerErrors(CapturedOutput output) throws Exception {
        requestId(mockMvc.perform(get("/api/v1/legal-documents/log-probe/invalid").param("number", "invalid"))
                .andExpect(status().isBadRequest()).andReturn());
        MvcResult error = mockMvc.perform(get("/api/v1/legal-documents/log-probe/error"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(error.getResponse().getContentAsString()).doesNotContain("로컬 로그 검증 예외");
        assertThat(output.getOut()).contains("[requestId=" + requestId(error) + "]")
                .contains("예상하지 못한 오류가 발생했습니다.", "IllegalStateException: 로컬 로그 검증 예외", "status=500");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("정상 상태 점검 요청은 헤더만 반환하고 완료 로그를 생략한다")
    void omitsHealthyMonitoringLog(CapturedOutput output) throws Exception {
        String id = requestId(mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn());
        assertThat(output.getOut().lines().filter(line ->
                line.contains("[requestId=" + id + "]") && line.contains("HTTP 요청 완료"))).isEmpty();
        assertThat(MDC.get("requestId")).isNull();
    }

    private String requestId(MvcResult result) {
        String id = result.getResponse().getHeader("X-Request-ID");
        assertThat(id).isNotBlank();
        assertThat(UUID.fromString(id).version()).isEqualTo(4);
        return id;
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/legal-documents/log-probe/{probeId}")
        CommonResponse<String> probe(@PathVariable String probeId, @RequestParam(defaultValue = "1") int number) {
            LoggerFactory.getLogger(getClass()).info("로컬 로그 검증 요청 처리: probeId={}", probeId);
            if ("error".equals(probeId)) {
                throw new IllegalStateException("로컬 로그 검증 예외");
            }
            return CommonResponse.success(String.valueOf(MDC.get("requestId")));
        }

        @GetMapping("/api/internal/log-probe")
        CommonResponse<String> internal() {
            return CommonResponse.success(String.valueOf(MDC.get("requestId")));
        }
    }
}
