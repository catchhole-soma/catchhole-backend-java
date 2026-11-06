package org.monitoring.catchholebackend.domain.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * #387 같은 live 읽기·지문 시나리오를 실제 Flyway schema의 PostgreSQL에서 실행한다(집계 JPQL·JSONB 열). 이 작업 전용
 * localhost gh180_test DB만 허용하며 테스트 트랜잭션은 되돌려 다른 PostgreSQL 테스트의 fixture를 건드리지 않는다.
 */
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH180_POSTGRES_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh180_test")
@DisplayName("#387 자동 누적 live 읽기와 지문 (PostgreSQL)")
class AnalysisLiveStateReaderPostgresIntegrationTest extends AnalysisLiveStateReaderIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GH180_POSTGRES_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh180");
        properties.add("spring.datasource.password", () -> "gh180-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }
}
