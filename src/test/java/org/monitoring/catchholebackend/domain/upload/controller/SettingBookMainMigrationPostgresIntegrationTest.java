package org.monitoring.catchholebackend.domain.upload.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifies the integrated migration chain and BOOK behavior on a newly owned test DB. */
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH221_MAIN_MIGRATION_JDBC_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh221_java_main_test_[0-9]+")
@DisplayName("통합된 main과 설정집 PostgreSQL migration 검증")
class SettingBookMainMigrationPostgresIntegrationTest extends SettingBookAnalysisIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GH221_MAIN_MIGRATION_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh180");
        properties.add("spring.datasource.password", () -> "gh180-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Test
    @DisplayName("main V76 데이터와 migration 이력을 보존하며 V77부터 V79까지 업그레이드한다")
    void upgradesMainSchemaWithoutChangingExistingChatData() throws Exception {
        String schema = "main_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("GH221_MAIN_MIGRATION_JDBC_URL");
        try (var connection = java.sql.DriverManager.getConnection(url, "gh180", "gh180-test-only");
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String isolatedUrl = url + "?currentSchema=" + schema + ",public";
        var main = org.flywaydb.core.Flyway.configure().dataSource(isolatedUrl, "gh180", "gh180-test-only")
                .locations("classpath:db/migration").target("76").load();
        main.migrate();
        main.validate();
        assertThat(main.info().current().getVersion().toString()).isEqualTo("76");
        var baseline = java.util.Arrays.stream(main.info().applied())
                .map(info -> info.getVersion() + ":" + info.getChecksum()).toList();
        String work = java.util.UUID.randomUUID().toString();
        String question = java.util.UUID.randomUUID().toString();
        try (var connection = java.sql.DriverManager.getConnection(isolatedUrl, "gh180", "gh180-test-only");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO members(id,email,password_hash,phone_verified,email_verified,display_name,status,role,created_at,updated_at) VALUES (1,'upgrade@example.invalid','test',false,true,'검증 작가','ACTIVE','AUTHOR',now(),now())");
            statement.executeUpdate("INSERT INTO works(id,member_id,title,genre,latest_episode_no,created_at,updated_at) VALUES ('" + work + "',1,'기존 main 작품','FANTASY',0,now(),now())");
            statement.executeUpdate("INSERT INTO chat_questions(id,member_id,work_id,idempotency_key,input_hash,question,generation,current_attempt_id) VALUES ('" + question + "',1,'" + work + "','main-upgrade','" + "a".repeat(64) + "','보존할 질문',1,'" + java.util.UUID.randomUUID() + "')");
        }
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(isolatedUrl, "gh180", "gh180-test-only")
                .locations("classpath:db/migration").target("79").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(3);
        upgraded.validate();
        assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("79");
        assertThat(java.util.Arrays.stream(upgraded.info().applied()).limit(baseline.size())
                .map(info -> info.getVersion() + ":" + info.getChecksum()).toList()).isEqualTo(baseline);
        try (var connection = java.sql.DriverManager.getConnection(isolatedUrl, "gh180", "gh180-test-only");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT question FROM chat_questions WHERE id='" + question + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("보존할 질문");
        }
    }

    @Test
    @DisplayName("main migration을 보존하고 설정집 기준 시점과 서사 시간 schema를 적용한다")
    void preservesMainMigrationsAndAppliesBookReferenceAndNarrativeTimeMigrations() {
        assertThat(sql.queryForList("select script from flyway_schema_history where version in ('74','75','76','77','78','79') and success order by installed_rank", String.class))
                .containsExactly("V74__rename_character_setting_display_labels.sql", "V75__add_chat_execution.sql",
                        "V76__add_retrieval_index.sql", "V77__add_setting_book_analysis.sql",
                        "V78__setting_book_reference_time_and_current_protection.sql", "V79__character_narrative_time.sql");
        assertThat(sql.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name='setting_book_revisions' and column_name like 'reference_%' order by column_name", String.class))
                .containsExactly("reference_episode_no", "reference_time_kind");
        assertThat(sql.queryForList("select table_name from information_schema.tables where table_schema=current_schema()", String.class))
                .contains("setting_book_revisions", "setting_book_chunks", "chat_questions", "chat_runs", "retrieval_sources", "retrieval_chunks");
    }
}
