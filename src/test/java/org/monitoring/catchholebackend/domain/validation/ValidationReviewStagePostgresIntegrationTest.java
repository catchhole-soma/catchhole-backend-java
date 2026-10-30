package org.monitoring.catchholebackend.domain.validation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.validation.type.ValidationReviewStage;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;

/**
 * GH312 S4 검수 단계 projection(원고 목록 JPQL·배치 요약 native SQL·이력 JPQL)과 실패 release Job 재검수를 실제 PostgreSQL에서 다시 실행한다.
 * 상위 H2 테스트 메서드를 그대로 상속한다. Destructive fixture cleanup is guarded to a new disposable loopback DB, never .env/shared DB.
 */
// chat.recovery-delay-ms: 이 클래스와 무관한 채팅 복구 scheduler가 fixture TRUNCATE와 겹쳐 읽지 않도록 Context 시작 1회만 돌게 한다.
@SpringBootTest(properties={"spring.config.import=","spring.docker.compose.enabled=false","chat.recovery-delay-ms=3600000"}) @ActiveProfiles("test")
@TestPropertySource(locations="classpath:validation-execution-fixture.properties")
@EnabledIfEnvironmentVariable(named="GH310_EXECUTION_POSTGRES_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh310_execution_test")
@DisplayName("격리 PostgreSQL의 검수 단계 projection·무효화 시각·실패 release Job 재검수")
class ValidationReviewStagePostgresIntegrationTest extends ValidationReviewStageIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("GH310_EXECUTION_POSTGRES_JDBC_URL"));p.add("spring.datasource.username",()->"gh310");p.add("spring.datasource.password",()->"gh310-disposable-only");
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"validate");p.add("spring.flyway.enabled",()->"true");
    }
    /** Worker claim은 DB 전체의 대기 Job을 고르므로 매 테스트 전에 합성 작품·회원 그래프를 모두 지운다. */
    @BeforeEach void clean() {org.monitoring.catchholebackend.support.PostgresFixtureCleanup.truncate(jdbc,"TRUNCATE TABLE works,members CASCADE");}

    @Test @DisplayName("실제 Flyway V100·JPA validate에서 무효화 시각은 처음 무효화할 때만 기록되고 유효 report에는 저장할 수 없다")
    void flywayV100RecordsFirstInvalidation() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='100' and success",Integer.class)).isEqualTo(1);
        var f=fixture();
        assertThat(jdbc.queryForObject("select invalidated_at from validation_reports where id=?",java.sql.Timestamp.class,f.report())).isNull();
        assertThatThrownBy(()->jdbc.update("update validation_reports set invalidated_at=now() where id=?",f.report())).as("only INVALIDATED reports carry the time")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        confirmedSettingsChanged(f);
        var first=jdbc.queryForObject("select invalidated_at from validation_reports where id=?",java.sql.Timestamp.class,f.report());assertThat(first).isNotNull();
        confirmedSettingsChanged(f);
        assertThat(jdbc.queryForObject("select invalidated_at from validation_reports where id=?",java.sql.Timestamp.class,f.report())).as("repeated purge keeps the first time").isEqualTo(first);
        assertThat(history(f).getFirst().reviewStage()).isEqualTo(ValidationReviewStage.INVALIDATED);assertThat(history(f).getFirst().invalidatedAt()).isNotNull();
    }
}
