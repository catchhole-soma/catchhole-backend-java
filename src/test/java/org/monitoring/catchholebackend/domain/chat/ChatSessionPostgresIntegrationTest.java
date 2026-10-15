package org.monitoring.catchholebackend.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL V80 전체 마이그레이션과 일회성 대화 계약")
class ChatSessionPostgresIntegrationTest extends ChatSessionIntegrationTest {
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
    @DisplayName("main V79의 질문과 migration 이력을 보존하며 대화방 V80을 적용한다")
    void upgradesMainSchemaWithoutChangingExistingQuestions() throws Exception {
        String schema = "session_upgrade_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/chat_test";
        try (var connection = java.sql.DriverManager.getConnection(url, "chat_test", "test-only");
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String isolatedUrl = url + "?currentSchema=" + schema + ",public";
        var main = org.flywaydb.core.Flyway.configure()
                .dataSource(isolatedUrl, "chat_test", "test-only")
                .locations("classpath:db/migration").target("79").load();
        main.migrate();
        main.validate();
        var baseline = java.util.Arrays.stream(main.info().applied())
                .map(info -> info.getVersion() + ":" + info.getChecksum()).toList();
        String work = java.util.UUID.randomUUID().toString();
        String question = java.util.UUID.randomUUID().toString();
        try (var connection = java.sql.DriverManager.getConnection(isolatedUrl, "chat_test", "test-only");
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO members(id,email,password_hash,phone_verified,email_verified,display_name,status,role,created_at,updated_at) VALUES (1,'session-upgrade@example.invalid','test',false,true,'검증 작가','ACTIVE','AUTHOR',now(),now())");
            statement.executeUpdate("INSERT INTO works(id,member_id,title,genre,latest_episode_no,created_at,updated_at) VALUES ('" + work + "',1,'기존 main 작품','FANTASY',0,now(),now())");
            statement.executeUpdate("INSERT INTO chat_questions(id,member_id,work_id,idempotency_key,input_hash,question,generation,current_attempt_id) VALUES ('" + question + "',1,'" + work + "','session-upgrade','" + "a".repeat(64) + "','보존할 질문',1,'" + java.util.UUID.randomUUID() + "')");
        }
        var upgraded = org.flywaydb.core.Flyway.configure()
                .dataSource(isolatedUrl, "chat_test", "test-only")
                .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("80");
        assertThat(java.util.Arrays.stream(upgraded.info().applied()).limit(baseline.size())
                .map(info -> info.getVersion() + ":" + info.getChecksum()).toList()).isEqualTo(baseline);
        try (var connection = java.sql.DriverManager.getConnection(isolatedUrl, "chat_test", "test-only");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT question,session_id,sequence_no,submitted_at FROM chat_questions WHERE id='" + question + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("question")).isEqualTo("보존할 질문");
            assertThat(result.getObject("session_id")).isNull();
            assertThat(result.getObject("sequence_no")).isNull();
            assertThat(result.getObject("submitted_at")).isNull();
        }
    }
}
