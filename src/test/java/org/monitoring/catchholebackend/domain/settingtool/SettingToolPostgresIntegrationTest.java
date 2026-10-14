package org.monitoring.catchholebackend.domain.settingtool;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL에서 설정 조회·작품 collection·변경 감지 계약을 확인한다")
class SettingToolPostgresIntegrationTest extends SettingToolIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "setting_tool_test")
            .withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "setting_tool_test")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/setting_tool_test");
        registry.add("spring.datasource.username", () -> "setting_tool_test");
        registry.add("spring.datasource.password", () -> "test-only");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 상속한 wire fixture는 자체 registry와 Python 청크의 최소 projection을 사용한다.
        // 전체 Flyway와 실행 어댑터는 ChatExecutionPostgresIntegrationTest에서 함께 검증한다.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}
