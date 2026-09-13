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
    @DisplayName("main V43의 이메일 회원을 보존하며 연속 분석 V44~V54을 적용한다")
    void upgradesMainSchemaWithoutReplacingEmailMigration() throws Exception {
        String url = System.getenv("GH180_MIGRATION_JDBC_URL");
        assertThat(url).doesNotContain(":35432/");
        Flyway.configure().dataSource(url, "gh180", "gh180-test-only")
                .locations("classpath:db/migration").target("43").load().migrate();
        try (var connection = DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO members (email,password_hash,phone_number,phone_verified,email_verified,display_name,status,role,created_at,updated_at)
                    VALUES ('migration@example.invalid','test-only',NULL,false,true,'검증 작가','ACTIVE','AUTHOR',now(),now())
                    """);
            statement.executeUpdate("""
                    INSERT INTO works (id,member_id,title,genre,latest_episode_no,created_at,updated_at)
                    SELECT '11111111-1111-4111-8111-111111111111',id,'그룹 비교 보존','FANTASY',0,now(),now()
                    FROM members WHERE email='migration@example.invalid'
                    """);
            statement.executeUpdate("""
                    INSERT INTO analysis_jobs (id,work_id,job_type,status,created_at,updated_at)
                    VALUES ('22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111',
                            'SETTING_EXTRACTION','SUCCEEDED',now(),now())
                    """);
            statement.executeUpdate("""
                    INSERT INTO character_fact_comparison_batches
                    (id,work_id,analysis_job_id,matched_character_id,canonical_fact_type,status,candidate_count,
                     base_snapshot_version,created_at,updated_at)
                    VALUES ('33333333-3333-4333-8333-333333333333','11111111-1111-4111-8111-111111111111',
                            '22222222-2222-4222-8222-222222222222',NULL,'STATUS','COMPLETED',2,0,now(),now())
                    """);
        }
        Flyway upgraded = Flyway.configure().dataSource(url, "gh180", "gh180-test-only")
                .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(11);
        upgraded.validate();
        assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("54");
        try (var connection = DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT email_verified,phone_number FROM members WHERE email='migration@example.invalid'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).isTrue();
            assertThat(row.getString(2)).isNull();
        }
        try (var connection = DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT matched_character_id,provisional_subject_key FROM character_fact_comparison_batches")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getObject(1)).isNull();
            assertThat(row.getString(2)).isNull();
        }
    }
}
