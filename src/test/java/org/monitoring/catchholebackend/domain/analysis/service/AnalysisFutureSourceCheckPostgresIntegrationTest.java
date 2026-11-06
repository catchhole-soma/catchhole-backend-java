package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 실제 Flyway schema·V77 CHECK에서 세 조회와 자동 생성의 표지 저장을 확인한다. 일회용 컨테이너만 사용한다. */
@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("#387 자동 누적 생성의 미래 출처 scalar 조회 — PostgreSQL")
class AnalysisFutureSourceCheckPostgresIntegrationTest extends AnalysisFutureSourceCheckScenarios {
    @Container static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh387_future_source_test").withEnv("POSTGRES_USER", "gh387_test")
            .withEnv("POSTGRES_PASSWORD", "disposable-gh387-test-only").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/gh387_future_source_test");
        p.add("spring.datasource.username", () -> "gh387_test");
        p.add("spring.datasource.password", () -> "disposable-gh387-test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
    }
}
