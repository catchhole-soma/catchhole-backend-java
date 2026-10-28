package org.monitoring.catchholebackend.domain.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="GH306_POSTGRES_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh306_test")
@DisplayName("GH306 실제 PostgreSQL 고정 자료와 Java/Python HTTP")
class ValidationDataPostgresIntegrationTest extends ValidationDataIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GH306_POSTGRES_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh306");
        properties.add("spring.datasource.password", () -> "gh306-disposable-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
    }
}
