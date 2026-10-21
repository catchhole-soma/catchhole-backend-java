package org.monitoring.catchholebackend.domain.relationship.controller;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("가계도 PostgreSQL 조회 권한·유효성·동일 MVCC 스냅샷 계약")
class FamilyTreePostgresIntegrationTest extends FamilyTreeControllerIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "gh261")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "gh261_test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/gh261_test");
        properties.add("spring.datasource.username", () -> "gh261");
        properties.add("spring.datasource.password", () -> "test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }
}
