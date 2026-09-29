package org.monitoring.catchholebackend.global.config.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

@DisplayName("HTTP requestId 필터 수명")
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("같은 스레드의 후속 요청에 새 ID를 부여하고 다른 MDC 값은 유지한다")
    void isolatesSequentialRequests() throws Exception {
        List<String> ids = new ArrayList<>();
        MDC.put("businessContext", "preserve-me");
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/works"), response, (request, result) -> {
                ids.add(MDC.get("requestId"));
                assertThat(response.getHeader("X-Request-ID")).isEqualTo(MDC.get("requestId"));
            });
            assertThat(MDC.get("requestId")).isNull();
            assertThat(MDC.get("businessContext")).isEqualTo("preserve-me");
        }
        assertThat(ids).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list.getFirst().getMDCPropertyMap().get("requestId")).isEqualTo(ids.getFirst());
    }

    @Test
    @DisplayName("처리되지 않은 예외도 MDC를 정리하고 ERROR 디스패치에 같은 ID를 쓴다")
    void retainsIdAcrossErrorDispatchWithoutDuplicateCompletion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/works");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("필터 검증 예외");
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw failure;
        })).isSameAs(failure);
        String id = response.getHeader("X-Request-ID");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("status=500");

        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, request.getRequestURI());
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("requestId")).isEqualTo(id));
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(id);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(logs.list).hasSize(1);
    }

    @Test
    @DisplayName("동시에 실행 중인 요청은 서로의 MDC를 덮어쓰지 않는다")
    void isolatesConcurrentRequests() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> {
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/works"), response, (req, res) -> {
                        String id = MDC.get("requestId");
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new ServletException(exception);
                        }
                        assertThat(MDC.get("requestId")).isEqualTo(id);
                        assertThat(response.getHeader("X-Request-ID")).isEqualTo(id);
                    });
                    assertThat(MDC.get("requestId")).isNull();
                    return response.getHeader("X-Request-ID");
                }));
            }
            assertThat(tasks.getFirst().get(10, TimeUnit.SECONDS))
                    .isNotNull().isNotEqualTo(tasks.getLast().get(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"/healthz,200,0", "/healthz,503,1", "/actuator/health,200,0", "/actuator/health,503,1",
            "/actuator/prometheus,200,0", "/actuator/prometheus,500,1"})
    @DisplayName("모니터링 성공만 완료 로그를 생략하고 헤더와 실패 로그는 유지한다")
    void limitsMonitoringLogs(String path, int status, int logCount) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response,
                (req, res) -> response.setStatus(status));
        assertThat(response.getHeader("X-Request-ID")).isNotBlank();
        assertThat(logs.list).hasSize(logCount);
        assertThat(MDC.get("requestId")).isNull();
    }
}
