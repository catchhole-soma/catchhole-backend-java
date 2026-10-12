package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 전체 Flyway·JPA validate와 채팅 계약")
class ChatExecutionPostgresIntegrationTest extends ChatExecutionIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
                    .withEnv("POSTGRES_USER", "chat_test")
                    .withEnv("POSTGRES_PASSWORD", "test-only")
                    .withEnv("POSTGRES_DB", "chat_test")
                    .withExposedPorts(5432)
                    .waitingFor(
                            org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRES.getHost()
                                + ":"
                                + POSTGRES.getMappedPort(5432)
                                + "/chat_test");
        r.add("spring.datasource.username", () -> "chat_test");
        r.add("spring.datasource.password", () -> "test-only");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("분석 Job이 없는 채팅 사용량도 정확히 하나의 소유자 FK를 가진다")
    void usageOwnerConstraint() {
        var r = start();
        reserve(r, java.util.UUID.randomUUID(), 100);
        tx.executeWithoutResult(
                s -> {
                    var usage = usages.findAllByChatRunId(r.view().attemptId()).getFirst();
                    assertThat(usage.getAnalysisJob()).isNull();
                    assertThat(usage.getChatRun().getId()).isEqualTo(r.view().attemptId());
                });
    }
}
