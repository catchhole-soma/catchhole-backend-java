package org.monitoring.catchholebackend.domain.upload.controller;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext
@EnabledIfEnvironmentVariable(named="GH221_POSTGRES_JDBC_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh221_refinement_test")
class SettingBookCommittedAcceptancePostgresIntegrationTest extends SettingBookCommittedAcceptanceIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("GH221_POSTGRES_JDBC_URL"));
        p.add("spring.datasource.username", () -> "gh180"); p.add("spring.datasource.password", () -> "gh180-test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver"); p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true"); p.add("spring.docker.compose.enabled", () -> "false");
    }
}
