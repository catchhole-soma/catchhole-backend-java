package org.monitoring.catchholebackend.domain.upload.controller;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH221_POSTGRES_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh221_refinement_test")
class SettingBookRevisionCasPostgresIntegrationTest extends SettingBookRevisionCasIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GH221_POSTGRES_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh180"); properties.add("spring.datasource.password", () -> "gh180-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver"); properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true"); properties.add("spring.docker.compose.enabled", () -> "false");
    }
}
