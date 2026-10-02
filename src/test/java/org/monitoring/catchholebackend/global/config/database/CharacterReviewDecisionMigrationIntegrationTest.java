package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 검토 결정 migration과 JPA 매핑")
class CharacterReviewDecisionMigrationIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "gh215")
            .withEnv("POSTGRES_PASSWORD", "gh215-test-only")
            .withEnv("POSTGRES_DB", "gh215_review_migration")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/gh215_review_migration");
        properties.add("spring.datasource.username", () -> "gh215");
        properties.add("spring.datasource.password", () -> "gh215-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entities;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @DisplayName("V1부터 V73까지 적용하고 이전 후보 기본값과 검토 방식·버전의 일관성 제약을 검증한다")
    void validatesMigrationAndReviewBasisConstraints() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("73");
        UUID id = new TransactionTemplate(transactions).execute(status -> {
            Member member = Member.register("gh215@example.invalid", "test-only", null, "검증 작가");
            entities.persist(member);
            Work work = Work.create(member, "검토 migration 검증", WorkGenre.FANTASY, null);
            entities.persist(work);
            SettingCandidate candidate = SettingCandidate.create(work, null, null, null, SettingEntityType.CHARACTER,
                    "아리아", null, null, null, "age", "20", SettingValueType.NUMBER,
                    JsonNodeFactory.instance.objectNode().put("value", 20), JsonNodeFactory.instance.arrayNode(), null, null);
            candidate.recordUserModification();
            entities.persist(candidate);
            return candidate.getId();
        });
        assertThat(jdbc.queryForObject("SELECT reviewed_application_mode FROM setting_candidates WHERE id=?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT reviewed_snapshot_version FROM setting_candidates WHERE id=?", Long.class, id)).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE setting_candidates SET reviewed_application_mode='APPLY_PROPOSAL' WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE setting_candidates SET reviewed_application_mode='APPLY_PROPOSAL',reviewed_snapshot_version=0 WHERE id=?", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE setting_candidates SET reviewed_snapshot_version=-1 WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE setting_candidates SET reviewed_application_mode='NOT_A_MODE' WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        String savedMode = new TransactionTemplate(transactions).execute(status -> entities.find(SettingCandidate.class, id)
                .getReviewedApplicationMode().name());
        assertThat(savedMode).isEqualTo("APPLY_PROPOSAL");
    }

    @Test
    @DisplayName("내용 수정 컬럼은 기존 행을 NULL로 보존하고 새 Worker INSERT에만 false 기본값을 적용한다")
    void contentEditMigrationSeparatesLegacyRowsAndNewWorkerInserts() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V73__distinguish_character_content_edit.sql")) {
            sql = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String migrationSql = sql.replace("setting_candidates", "gh215_legacy_candidates");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("CREATE TEMP TABLE gh215_legacy_candidates (id integer PRIMARY KEY, user_modified boolean NOT NULL) ON COMMIT DROP");
            jdbc.update("INSERT INTO gh215_legacy_candidates VALUES (1,true),(2,false)");
            jdbc.execute(migrationSql);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gh215_legacy_candidates WHERE user_content_modified IS NULL", Long.class))
                    .isEqualTo(2L);
            jdbc.update("INSERT INTO gh215_legacy_candidates(id,user_modified) VALUES (3,false)");
            assertThat(jdbc.queryForObject("SELECT user_content_modified FROM gh215_legacy_candidates WHERE id=3", Boolean.class)).isFalse();
        });
    }
}
