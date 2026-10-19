package org.monitoring.catchholebackend.global.config.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.docker.compose.enabled=false",
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
})
@ActiveProfiles("test")
@AutoConfigureMetrics
@Import(SecurityRejectionMetricsIntegrationTest.ProbeConfig.class)
@DisplayName("인증 거절 HTTP 응답의 Prometheus API 경로 계측")
class SecurityRejectionMetricsIntegrationTest {

    @Autowired
    private Environment environment;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private ProbeController probe;

    private final List<Member> members = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetProbe() {
        probe.handlerCalls.set(0);
        probe.interceptorCalls.set(0);
    }

    @AfterEach
    void deleteMembers() {
        memberRepository.deleteAll(members);
    }

    @Test
    @DisplayName("인증 없는 실제 API의 401을 경로별로 구분하고 작품 ID와 쿼리는 라벨에 넣지 않는다")
    void groupsMissingTokenRequestsByRegisteredRoute() throws Exception {
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        String template = "/api/v1/works/{workId}/episodes";
        double beforeMe = count("GET", "/api/v1/auth/me", 401);
        double beforeEpisodes = count("GET", template, 401);

        assertUnauthorized(request("GET", "/api/v1/auth/me", null));
        assertUnauthorized(request("GET", "/api/v1/works/" + firstId + "/episodes?probe=private-query", null));
        assertUnauthorized(request("GET", "/api/v1/works/" + secondId + "/episodes", null));

        assertCount("GET", "/api/v1/auth/me", 401, beforeMe + 1);
        assertCount("GET", template, 401, beforeEpisodes + 2);
        assertThat(scrape()).doesNotContain(firstId, secondId, "private-query");
    }

    @Test
    @DisplayName("만료 JWT의 401은 API 패턴만 보완하고 컨트롤러·인터셉터를 실행하지 않는다")
    void attributesExpiredTokenWithoutExecutingApplicationCode() throws Exception {
        String template = "/api/v1/metric-probes/{probeId}";
        double before = count("GET", template, 401);
        String token = token("1", Instant.now().minusSeconds(120));

        assertUnauthorized(request("GET", "/api/v1/metric-probes/expired", token));

        assertCount("GET", template, 401, before + 1);
        assertThat(probe.handlerCalls.get()).isZero();
        assertThat(probe.interceptorCalls.get()).isZero();
        assertThat(scrape()).doesNotContain(token);
    }

    @Test
    @DisplayName("유효한 JWT의 정상 처리와 권한 부족 403의 기존 응답을 유지한다")
    void preservesSuccessfulAndForbiddenRequests() throws Exception {
        Member member = memberRepository.saveAndFlush(Member.register(
                "metrics-" + UUID.randomUUID() + "@example.test", "unused-password-hash", null, "계측 검증"));
        members.add(member);
        String token = token(member.getId().toString(), Instant.now().plusSeconds(300));
        String allowed = "/api/v1/metric-probes/{probeId}";
        String forbidden = "/api/v1/admin/world-images/metric-probe";
        double beforeAllowed = count("GET", allowed, 200);
        double beforeForbidden = count("GET", forbidden, 403);

        assertThat(request("GET", "/api/v1/metric-probes/allowed", token).statusCode()).isEqualTo(200);
        HttpResponse<String> denied = request("GET", forbidden, token);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("AUTH_FORBIDDEN");

        assertCount("GET", allowed, 200, beforeAllowed + 1);
        assertCount("GET", forbidden, 403, beforeForbidden + 1);
        assertThat(probe.handlerCalls.get()).isEqualTo(1);
        assertThat(probe.interceptorCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 URL도 HTTP 메서드와 구체적인 MVC 매핑에 맞는 패턴으로 구분한다")
    void respectsMethodAndMappingSpecificity() throws Exception {
        String literal = "/api/v1/metric-probes/actions";
        String variable = "/api/v1/metric-probes/{probeId}";
        double beforeGet = count("GET", literal, 401);
        double beforePost = count("POST", variable, 401);

        assertUnauthorized(request("GET", literal, null));
        assertUnauthorized(request("POST", literal, "invalid-jwt"));

        assertCount("GET", literal, 401, beforeGet + 1);
        assertCount("POST", variable, 401, beforePost + 1);
        assertThat(probe.handlerCalls.get()).isZero();
        assertThat(probe.interceptorCalls.get()).isZero();
    }

    @Test
    @DisplayName("Worker API 키가 없는 내부 요청도 등록된 API 패턴으로 집계한다")
    void attributesInternalApiKeyRejections() throws Exception {
        String id = UUID.randomUUID().toString();
        String template = "/api/internal/v1/analysis-jobs/{analysisJobId}/heartbeat";
        double before = count("POST", template, 401);

        assertUnauthorized(request("POST", "/api/internal/v1/analysis-jobs/" + id + "/heartbeat", null));

        assertCount("POST", template, 401, before + 1);
        assertThat(scrape()).doesNotContain(id);
    }

    @Test
    @DisplayName("미등록 URL과 잘못된 메서드의 인증 거절은 UNKNOWN으로 유지한다")
    void keepsUnmappedRequestsUnknown() throws Exception {
        String unknownId = UUID.randomUUID().toString();
        double beforeGet = count("GET", "UNKNOWN", 401);
        double beforeDelete = count("DELETE", "UNKNOWN", 401);

        assertUnauthorized(request("GET", "/api/v1/not-a-route/" + unknownId, null));
        assertUnauthorized(request("DELETE", "/api/v1/metric-probes/actions", null));

        assertCount("GET", "UNKNOWN", 401, beforeGet + 1);
        assertCount("DELETE", "UNKNOWN", 401, beforeDelete + 1);
        assertThat(scrape()).doesNotContain(unknownId);
    }

    private String token(String subject, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(subject)
                .issuedAt(expiresAt.minusSeconds(600)).expiresAt(expiresAt).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("AUTH_UNAUTHORIZED");
    }

    private String scrape() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.management.port") + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private double count(String method, String uri, int status) throws Exception {
        return scrape().lines().filter(line -> line.startsWith("http_server_requests_seconds_count{"))
                .filter(line -> line.contains("method=\"" + method + "\"")
                        && line.contains("uri=\"" + uri + "\"") && line.contains("status=\"" + status + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1))).sum();
    }

    private void assertCount(String method, String uri, int status, double expected) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(count(method, uri, status)).isEqualTo(expected));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig implements WebMvcConfigurer {
        private final ProbeController controller = new ProbeController();

        @Bean
        ProbeController metricProbeController() {
            return controller;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    controller.interceptorCalls.incrementAndGet();
                    return true;
                }
            }).addPathPatterns("/api/v1/metric-probes/**", "/api/v1/admin/world-images/metric-probe");
        }
    }

    @RestController
    static class ProbeController {
        final AtomicInteger handlerCalls = new AtomicInteger();
        final AtomicInteger interceptorCalls = new AtomicInteger();

        @GetMapping({"/api/v1/metric-probes/{probeId}", "/api/v1/metric-probes/actions",
                "/api/v1/admin/world-images/metric-probe"})
        String get() {
            handlerCalls.incrementAndGet();
            return "ok";
        }

        @PostMapping("/api/v1/metric-probes/{probeId}")
        String post() {
            handlerCalls.incrementAndGet();
            return "ok";
        }
    }
}
