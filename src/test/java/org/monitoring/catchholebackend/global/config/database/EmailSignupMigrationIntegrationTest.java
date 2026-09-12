package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 이메일 가입 migration")
class EmailSignupMigrationIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "migration_test")
            .withEnv("POSTGRES_PASSWORD", "migration-test-only")
            .withEnv("POSTGRES_DB", "email_signup")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\n", 2));

    @Test
    @DisplayName("V41 기존 회원의 전화번호는 보존하고 V42 이후 여러 이메일 회원의 NULL 전화번호를 허용한다")
    void preservesExistingMembersAndAllowsMultipleNullPhoneNumbers() throws Exception {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/email_signup";
        Flyway.configure().dataSource(url, "migration_test", "migration-test-only")
                .locations("classpath:db/migration").target("41").load().migrate();
        try (var connection = DriverManager.getConnection(url, "migration_test", "migration-test-only");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO members (email,password_hash,phone_number,phone_verified,display_name,status,role,created_at,updated_at)
                    VALUES ('legacy@example.com','test-hash','01012345678',true,'기존 작가','ACTIVE','AUTHOR',now(),now())
                    """);
        }
        Flyway.configure().dataSource(url, "migration_test", "migration-test-only")
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url, "migration_test", "migration-test-only");
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT phone_number,phone_verified,email_verified FROM members WHERE email='legacy@example.com'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("01012345678");
                assertThat(row.getBoolean(2)).isTrue();
                assertThat(row.getBoolean(3)).isFalse();
            }
            for (String email : new String[]{"one@example.com", "two@example.com"}) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO members (email,password_hash,phone_number,phone_verified,email_verified,display_name,status,role,created_at,updated_at)
                        VALUES (?,'test-hash',NULL,false,true,'작가','ACTIVE','AUTHOR',now(),now())
                        """)) {
                    insert.setString(1, email);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
            }
            try (var row = statement.executeQuery("SELECT count(*) FROM members WHERE phone_number IS NULL AND email_verified=true")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(2);
            }
        }
    }
}
