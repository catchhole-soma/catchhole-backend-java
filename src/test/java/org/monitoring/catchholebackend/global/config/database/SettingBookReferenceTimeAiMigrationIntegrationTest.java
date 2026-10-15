package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Prepares only a new, explicitly selected AI test database for the shared SQLAlchemy fence checks. */
@EnabledIfEnvironmentVariable(named = "GH221_AI_MIGRATION_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh180_ai_test")
class SettingBookReferenceTimeAiMigrationIntegrationTest {
    @Test void migrateIsolatedAiSchemaThroughNarrativeTime() throws Exception {
        String url = System.getenv("GH221_AI_MIGRATION_JDBC_URL");
        assertThat(url).doesNotContain("gh221_local", ":35432/");
        var flyway = Flyway.configure().dataSource(url, "gh180", "gh180-test-only").locations("classpath:db/migration").load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("79");
        try (var connection = DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement();
             var columns = statement.executeQuery("select column_name from information_schema.columns where table_name='setting_book_revisions' and column_name like 'reference_%' order by column_name")) {
            assertThat(columns.next()).isTrue(); assertThat(columns.getString(1)).isEqualTo("reference_episode_no");
            assertThat(columns.next()).isTrue(); assertThat(columns.getString(1)).isEqualTo("reference_time_kind");
            assertThat(columns.next()).isFalse();
        }
    }
}
