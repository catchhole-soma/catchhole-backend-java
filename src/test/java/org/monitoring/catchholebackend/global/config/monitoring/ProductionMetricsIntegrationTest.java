package org.monitoring.catchholebackend.global.config.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.monitoring.catchholebackend.global.config.logging.RequestLoggingConfig;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = ProductionMetricsIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.location=classpath:application.yml,classpath:application-prod.yml",
                "spring.docker.compose.enabled=false",
                "management.server.port=0"
        }
)
@ActiveProfiles("prod")
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("운영 메트릭 포트와 기존 health 경로 분리")
class ProductionMetricsIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private AtomicBoolean dependencyDown;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @AfterEach
    void restoreDependency() {
        dependencyDown.set(false);
    }

    @Test
    @DisplayName("메트릭은 관리 포트에만 제공하고 운영 라벨과 HTTP histogram을 포함한다")
    void exposeMetricsOnlyOnManagementPort() throws Exception {
        int applicationPort = port("local.server.port");
        int managementPort = port("local.management.port");
        assertThat(applicationPort).isNotEqualTo(managementPort);

        // 실제 HTTP 요청으로 Timer를 생성한다. 운영 DB나 외부 서비스에 연결하지 않는다.
        assertThat(get(applicationPort, "/healthz").statusCode()).isEqualTo(200);
        assertThat(get(applicationPort, "/actuator/health").statusCode()).isEqualTo(200);

        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains(
                "environment=\"prod\"", "application=\"catchhole-backend\"",
                "http_server_requests_seconds_count", "http_server_requests_seconds_bucket");

        HttpResponse<String> publicPort = get(applicationPort, "/actuator/prometheus");
        assertThat(publicPort.statusCode()).isBetween(400, 499);
        assertThat(publicPort.body()).doesNotContain("# HELP", "process_cpu_usage");
    }

    @Test
    @DisplayName("기존 API 포트의 health 경로와 관리 포트 모두 정상 상태를 반환한다")
    void preserveHealthPathOnApplicationPort() throws Exception {
        for (int port : new int[]{port("local.server.port"), port("local.management.port")}) {
            HttpResponse<String> health = get(port, "/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"")
                    .doesNotContain("components", "details");
        }
    }

    @Test
    @DisplayName("의존성이 비정상이면 기존 health 경로도 상세 정보 없이 503을 반환한다")
    void preserveUnhealthyStatusWithoutExposingDetails() throws Exception {
        dependencyDown.set(true);
        for (int port : new int[]{port("local.server.port"), port("local.management.port")}) {
            HttpResponse<String> health = get(port, "/actuator/health");
            assertThat(health.statusCode()).isEqualTo(503);
            assertThat(health.body()).contains("\"status\":\"DOWN\"")
                    .doesNotContain("internal-diagnostic", "components", "details");
        }
    }

    @Test
    @DisplayName("운영 API·관리 포트의 실제 HTTP 응답과 오류 로그에 같은 requestId를 남긴다")
    void correlatesProductionHttpResponsesAndLogs(CapturedOutput output) throws Exception {
        for (int port : new int[]{port("local.server.port"), port("local.management.port")}) {
            HttpResponse<String> health = get(port, "/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.headers().firstValue("X-Request-ID")).isPresent();
        }
        HttpResponse<String> healthz = get(port("local.server.port"), "/healthz");
        String healthyId = healthz.headers().firstValue("X-Request-ID").orElseThrow();
        dependencyDown.set(true);
        HttpResponse<String> unhealthy = get(port("local.server.port"), "/actuator/health");
        String errorId = unhealthy.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(unhealthy.statusCode()).isEqualTo(503);
        assertThat(errorId).isNotEqualTo(healthyId);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(output.getOut().lines().filter(line ->
                        line.contains("[requestId=" + errorId + "]") && line.contains("status=503")))
                        .hasSize(1));
        assertThat(output.getOut().lines().filter(line ->
                line.contains("[requestId=" + healthyId + "]") && line.contains("HTTP 요청 완료"))).isEmpty();
    }

    private int port(String property) {
        return environment.getRequiredProperty(property, Integer.class);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataRedisAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @Import({ProductionHealthWebConfig.class, RequestLoggingConfig.class})
    static class TestApplication {

        @Bean
        AtomicBoolean dependencyDown() {
            return new AtomicBoolean();
        }

        @Bean
        HealthIndicator fixtureHealthIndicator(AtomicBoolean dependencyDown) {
            return () -> dependencyDown.get()
                    ? Health.down().withDetail("internal-diagnostic", "fixture").build()
                    : Health.up().build();
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            // 운영과 같은 공개 경로를 사용하며, 인증 도메인·DB 없이 HTTP 노출 경계를 검증한다.
            return http.authorizeHttpRequests(auth -> auth
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(SecurityConstant.PUBLIC_URLS).permitAll()
                    .anyRequest().denyAll()).build();
        }
    }
}
