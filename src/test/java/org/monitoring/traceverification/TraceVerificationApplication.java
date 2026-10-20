package org.monitoring.traceverification;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.aitoken.controller.AiTokenWorkerController;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.dto.response.AiTokenReservationResponse;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.global.common.response.CommonResponse;
import org.monitoring.catchholebackend.global.config.security.InternalApiKeyAuthenticationFilter;
import org.monitoring.catchholebackend.global.config.security.InternalApiProperties;
import org.monitoring.catchholebackend.global.config.security.SecurityErrorResponseWriter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Opt-in local HTTP/JDBC tracing fixture, never packaged in the application JAR.
 * Uses the production HTTP controller/DTOs/filter but a synthetic H2 ledger, not
 * AiTokenServiceImpl or production authorization/lease/account state.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
})
@Import({AiTokenWorkerController.class, SecurityErrorResponseWriter.class, TraceVerificationApplication.FixtureErrors.class})
public class TraceVerificationApplication {

    public static final String API_KEY = "gh271-api-key-canary";
    public static final String ERROR_MODEL = "gh271-error-canary";
    public static final String DELAY_MODEL = "gh271-delay-canary";

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parseOptions(args);
        Path readyFile = Path.of(options.get("ready-file")).toAbsolutePath();
        if (!Files.isDirectory(readyFile.getParent()) || Files.exists(readyFile)) {
            throw new IllegalArgumentException("Ready file must be a new file in an existing directory.");
        }
        int port = Integer.parseInt(options.getOrDefault("port", "0"));
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535.");
        }
        String address = options.getOrDefault("address", "127.0.0.1");
        if (!address.equals("127.0.0.1") && !address.equals("0.0.0.0")) {
            throw new IllegalArgumentException("Only loopback or explicit container bind is accepted.");
        }

        // Do not load application.yml, .env, Spring environment variables, or
        // runtime system properties. The Agent reads its own explicit config.
        StandardEnvironment environment = new StandardEnvironment();
        var sourceNames = new ArrayList<String>();
        environment.getPropertySources().forEach(source -> sourceNames.add(source.getName()));
        sourceNames.forEach(environment.getPropertySources()::remove);
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-trace-fixture", Map.of(
                "spring.config.name", "gh271-trace-verification-does-not-exist",
                "spring.main.banner-mode", "off",
                "spring.application.name", "catchhole-trace-verification",
                "server.address", address,
                "server.port", Integer.toString(port),
                "logging.level.root", "OFF",
                "spring.docker.compose.enabled", "false",
                "management.endpoints.enabled-by-default", "false"
        )));
        SpringApplication application = new SpringApplication(TraceVerificationApplication.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        ConfigurableApplicationContext context = application.run();
        try {
            int actualPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            Files.writeString(readyFile,
                    "{\"port\":" + actualPort + ",\"fixture\":\"synthetic-h2-token-ledger\"}\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            context.close();
            throw failure;
        }
    }

    static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        String prefix = "--trace.verification.";
        for (String arg : args) {
            if (!arg.startsWith(prefix) || !arg.contains("=")) {
                throw new IllegalArgumentException("Only explicit trace fixture options are accepted.");
            }
            String[] parts = arg.substring(prefix.length()).split("=", 2);
            if ((!parts[0].equals("ready-file") && !parts[0].equals("port")
                    && !parts[0].equals("address"))
                    || parts[1].isBlank() || options.putIfAbsent(parts[0], parts[1]) != null) {
                throw new IllegalArgumentException("Unknown, blank, or duplicate fixture option.");
            }
        }
        if (!options.containsKey("ready-file")) {
            throw new IllegalArgumentException("An explicit trace.verification.ready-file is required.");
        }
        return options;
    }

    @Bean(destroyMethod = "close")
    SyntheticLedger syntheticLedger() throws SQLException {
        return new SyntheticLedger();
    }

    @Bean
    AiTokenService aiTokenService(SyntheticLedger ledger) {
        // Import the real HTTP controller without loading the production app or
        // claiming that this small transport fixture validates business policy.
        return (AiTokenService) Proxy.newProxyInstance(
                AiTokenService.class.getClassLoader(), new Class<?>[]{AiTokenService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "reserve" -> ledger.reserve((AiTokenReserveRequest) args[0]);
                    case "settle", "release" -> {
                        ledger.complete((UUID) args[0]);
                        yield null;
                    }
                    case "toString" -> "synthetic-trace-verification-ledger";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Outside trace fixture scope.");
                });
    }

    @Bean
    FilterRegistrationBean<InternalApiKeyAuthenticationFilter> internalFilter(SecurityErrorResponseWriter writer) {
        var registration = new FilterRegistrationBean<>(
                new InternalApiKeyAuthenticationFilter(new InternalApiProperties(API_KEY), writer));
        registration.addUrlPatterns("/api/internal/*");
        return registration;
    }

    @RestControllerAdvice
    public static class FixtureErrors {
        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        CommonResponse<Void> deliberateFailure() {
            return CommonResponse.failure("Synthetic tracing verification failure.", null);
        }
    }

    static final class SyntheticLedger implements AutoCloseable {
        private final String jdbcUrl = "jdbc:h2:mem:trace_verification_" + UUID.randomUUID();
        private final Connection keeper;

        SyntheticLedger() throws SQLException {
            keeper = DriverManager.getConnection(jdbcUrl, "sa", "");
            try (var statement = keeper.createStatement()) {
                statement.execute("CREATE TABLE TRACE_LEDGER (REQUEST_ID UUID PRIMARY KEY, MODEL_NAME VARCHAR(100), FINISHED BOOLEAN)");
            }
        }

        AiTokenReservationResponse reserve(AiTokenReserveRequest request) throws SQLException, InterruptedException {
            try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                    var insert = connection.prepareStatement(
                            "MERGE INTO TRACE_LEDGER KEY(REQUEST_ID) VALUES (?, ?, FALSE) /* GH271_SQL_CANARY */");
                    var query = connection.prepareStatement(
                            "SELECT COUNT(*) FROM TRACE_LEDGER WHERE MODEL_NAME = ? /* GH271_SQL_CANARY */")) {
                insert.setObject(1, request.requestId());
                insert.setString(2, request.modelName());
                insert.executeUpdate();
                query.setString(1, request.modelName());
                try (var result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("Synthetic ledger did not return a row.");
                    }
                }
            }
            if (ERROR_MODEL.equals(request.modelName())) {
                throw new IllegalStateException(ERROR_MODEL);
            }
            if (DELAY_MODEL.equals(request.modelName())) {
                Thread.sleep(150);
            }
            return new AiTokenReservationResponse(request.requestId(), request.reservedTokens(), AiTokenUsageStatus.RESERVED);
        }

        void complete(UUID requestId) throws SQLException {
            try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                    var statement = connection.prepareStatement(
                            "UPDATE TRACE_LEDGER SET FINISHED = TRUE WHERE REQUEST_ID = ? /* GH271_SQL_CANARY */")) {
                statement.setObject(1, requestId);
                statement.executeUpdate();
            }
        }

        @Override
        public void close() throws SQLException {
            keeper.close();
        }
    }
}
