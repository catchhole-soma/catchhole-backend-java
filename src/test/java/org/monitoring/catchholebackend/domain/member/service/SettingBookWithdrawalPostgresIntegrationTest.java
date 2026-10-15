package org.monitoring.catchholebackend.domain.member.service;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Verifies account cleanup against a fresh, fully migrated database. */
@SpringBootTest(properties = {"spring.config.import=", "work.purge.worker-drain=0s", "member.withdrawal.retry-delay=0s"})
@Testcontainers(disabledWithoutDocker = true)
class SettingBookWithdrawalPostgresIntegrationTest extends MemberWithdrawalIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh221_account_purge_test")
            .withEnv("POSTGRES_USER", "gh221_test")
            .withEnv("POSTGRES_PASSWORD", "gh221-disposable-test-only")
            .withExposedPorts(5432)
            .withLabel("catchhole.fixture", "gh221-disposable")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/gh221_account_purge_test");
        properties.add("spring.datasource.username", () -> "gh221_test");
        properties.add("spring.datasource.password", () -> "gh221-disposable-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }
}
