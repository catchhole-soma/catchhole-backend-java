package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "GH180_MIGRATION_JDBC_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh180_migration_test")
class OrderedAnalysisMigrationIntegrationTest {
    @Test
    @DisplayName("main V42의 이메일 회원을 보존하며 연속 분석 V43~V53을 적용한다")
    void upgradesMainSchemaWithoutReplacingEmailMigration() throws Exception {
        String url = System.getenv("GH180_MIGRATION_JDBC_URL");
        assertThat(url).doesNotContain(":35432/");
        Flyway.configure().dataSource(url, "gh180", "gh180-test-only")
                .locations("classpath:db/migration").target("42").load().migrate();
        try (var connection = DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO members (email,password_hash,phone_number,phone_verified,email_verified,display_name,status,role,created_at,updated_at)
                    VALUES ('migration@example.invalid','test-only',NULL,false,true,'검증 작가','ACTIVE','AUTHOR',now(),now())
                    """);
        }
        Flyway upgraded = Flyway.configure().dataSource(url, "gh180", "gh180-test-only")
                .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(11);
        upgraded.validate();
        assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("53");
        try (var connection = DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT email_verified,phone_number FROM members WHERE email='migration@example.invalid'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).isTrue();
            assertThat(row.getString(2)).isNull();
        }
    }
}
