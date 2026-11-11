package org.monitoring.catchholebackend.domain.productusage.repository;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 전체 요청 완료 SQL")
class ProductUsageSourcePostgresIntegrationTest extends ProductUsageSourceIntegrationTest {
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "product_usage_source_test").withEnv("POSTGRES_USER", "usage_test")
            .withEnv("POSTGRES_PASSWORD", "test-only-password").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/product_usage_source_test");
        p.add("spring.datasource.username", () -> "usage_test");
        p.add("spring.datasource.password", () -> "test-only-password");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }
}
