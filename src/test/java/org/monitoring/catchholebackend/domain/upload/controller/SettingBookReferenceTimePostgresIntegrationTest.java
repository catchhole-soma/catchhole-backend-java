package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Existing local container, newly owned test database only; never opens the user's gh221_local DB. */
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH221_POSTGRES_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh221_refinement_test")
class SettingBookReferenceTimePostgresIntegrationTest extends SettingBookAnalysisIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GH221_POSTGRES_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh180");
        properties.add("spring.datasource.password", () -> "gh180-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Test void v78RetainsFrozenReferencesAndRejectsInvalidReferenceAndMarkerPairs() throws Exception {
        var character = manualCharacter(77);
        var book = uploadReference("하린은 기사다.", "EPISODE", 3);
        var revision = book.path("revisionId").asText();
        assertThat(sql.queryForObject("select count(*) from flyway_schema_history where version='78' and success", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("select reference_episode_no from setting_book_revisions where id=?::uuid", Integer.class, revision)).isEqualTo(3);
        rejectSql("update setting_book_revisions set reference_time_kind='WORK_START' where id='"+revision+"'");
        rejectSql("update setting_book_revisions set reference_episode_no=0 where id='"+revision+"'");
        rejectSql("update setting_book_revisions set reference_episode_no=null where id='"+revision+"'");
        rejectSql("update setting_book_revisions set reference_time_kind='UNSUPPORTED' where id='"+revision+"'");
        rejectSql("update characters set manually_edited_slots='{}'::jsonb where id='"+character.getId()+"'");
        assertThat(sql.queryForObject("select jsonb_typeof(manually_edited_slots) from characters where id=?", String.class, character.getId())).isEqualTo("array");
    }

    private void rejectSql(String statement) {
        sql.execute((ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var sqlStatement = connection.createStatement()) {
                assertThatThrownBy(() -> sqlStatement.executeUpdate(statement)).isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
            } finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
            return null;
        });
    }
}
