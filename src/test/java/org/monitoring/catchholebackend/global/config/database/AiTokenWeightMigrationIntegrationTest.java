package org.monitoring.catchholebackend.global.config.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 모델별 사용자 차감 migration")
class AiTokenWeightMigrationIntegrationTest {
    @Container
    private static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
                    .withEnv("POSTGRES_USER", "migration_test")
                    .withEnv("POSTGRES_PASSWORD", "migration-test-only")
                    .withEnv("POSTGRES_DB", "token_weight")
                    .withExposedPorts(5432)
                    .waitingFor(
                            org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    private static final UUID WORK = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID QUESTION = UUID.randomUUID();
    private static final UUID USER_RUN = UUID.randomUUID();
    private static final UUID SERVICE_RUN = UUID.randomUUID();

    @Test
    @DisplayName("기존 계정과 진행 예약은 1:1로 보존하고 서비스 부담·미정산·원시 사용량을 구분한다")
    void preservesLegacyAccountingAndUnknownWhileKeepingServicePaidChargesZero() throws Exception {
        String url =
                "jdbc:postgresql://"
                        + POSTGRES.getHost()
                        + ":"
                        + POSTGRES.getMappedPort(5432)
                        + "/token_weight";
        var baseline = flyway(url, "82");
        baseline.migrate();
        var existingMigrations = java.util.Arrays.stream(baseline.info().applied())
                .map(info -> info.getVersion() + ":" + info.getChecksum()).toList();
        UUID workerPending = UUID.randomUUID();
        UUID workerSettled = UUID.randomUUID();
        UUID workerReleased = UUID.randomUUID();
        UUID chatUnknown = UUID.randomUUID();
        UUID chatPending = UUID.randomUUID();
        UUID chatSettled = UUID.randomUUID();
        UUID servicePending = UUID.randomUUID();
        UUID serviceSettled = UUID.randomUUID();
        UUID serviceUnknown = UUID.randomUUID();
        try (var connection = connect(url)) {
            createLegacyOwners(connection);
            insertLegacyUsage(connection, workerPending, JOB, null, "RESERVED", 101, null, null);
            insertLegacyUsage(connection, workerSettled, JOB, null, "SETTLED", 201, 7L, 2L);
            insertLegacyUsage(connection, workerReleased, JOB, null, "RELEASED", 301, null, null);
            insertLegacyUsage(connection, chatUnknown, null, USER_RUN, "UNKNOWN", 401, null, null);
            insertLegacyUsage(connection, chatPending, null, USER_RUN, "RESERVED", 501, null, null);
            insertLegacyUsage(connection, chatSettled, null, USER_RUN, "SETTLED", 601, 22L, 5L);
            insertLegacyUsage(connection, servicePending, null, SERVICE_RUN, "RESERVED", 701, null, null);
            insertLegacyUsage(connection, serviceSettled, null, SERVICE_RUN, "SETTLED", 801, 500L, 0L);
            insertLegacyUsage(connection, serviceUnknown, null, SERVICE_RUN, "UNKNOWN", 901, null, null);
        }

        var migration = flyway(url, "83");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        migration.validate();
        assertThat(migration.info().current().getVersion().toString()).isEqualTo("83");
        assertThat(java.util.Arrays.stream(migration.info().applied()).limit(existingMigrations.size())
                .map(info -> info.getVersion() + ":" + info.getChecksum()).toList())
                .isEqualTo(existingMigrations);
        try (var connection = connect(url)) {
            assertAccountUnchanged(connection);
            assertUsage(connection, workerPending, "LEGACY_RAW_V1", 101, "101", null, null, null);
            assertUsage(connection, workerSettled, "LEGACY_RAW_V1", 201, "201", "9", 7L, 2L);
            assertUsage(connection, workerReleased, "LEGACY_RAW_V1", 301, "301", null, null, null);
            assertUsage(connection, chatUnknown, "LEGACY_RAW_V1", 401, "401", null, null, null);
            assertUsage(connection, chatPending, "LEGACY_RAW_V1", 501, "501", null, null, null);
            assertUsage(connection, chatSettled, "LEGACY_RAW_V1", 601, "601", "27", 22L, 5L);
            assertUsage(connection, servicePending, "SERVICE_PAID_V1", 701, "0", null, null, null);
            assertUsage(connection, serviceSettled, "SERVICE_PAID_V1", 801, "0", "0", 500L, 0L);
            assertUsage(connection, serviceUnknown, "SERVICE_PAID_V1", 901, "0", null, null, null);
            assertColumnTypes(connection);
            assertInvalidChargesRejected(connection, workerPending);

            // 정책 snapshot이 없는 구버전 쓰기를 0원 예약으로 받아들이지 않는다.
            assertThatThrownBy(
                            () ->
                                    insertLegacyUsage(
                                            connection, UUID.randomUUID(), JOB, null,
                                            "RESERVED", 20, null, null))
                    .isInstanceOfSatisfying(
                            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23502"));
        }
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, "migration_test", "migration-test-only")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, "migration_test", "migration-test-only");
    }

    private static void createLegacyOwners(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO members (id,email,password_hash,phone_number,phone_verified,
                        email_verified,display_name,status,role,created_at,updated_at)
                    VALUES (1,'legacy@example.com','test-only',NULL,false,true,'작가',
                        'ACTIVE','AUTHOR',now(),now())
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_token_accounts (member_id,granted_tokens,used_tokens,
                        reserved_tokens,created_at,updated_at)
                    VALUES (1,4000000,1234567,602,now(),now())
                    """);
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO works (id,member_id,title,genre,latest_episode_no,created_at,updated_at)
                VALUES (?,1,'합성 작품','FANTASY',1,now(),now())
                """)) {
            insert.setObject(1, WORK);
            insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO analysis_jobs (id,work_id,job_type,status,created_at,updated_at)
                VALUES (?,?,'SETTING_EXTRACTION','RUNNING',now(),now())
                """)) {
            insert.setObject(1, JOB);
            insert.setObject(2, WORK);
            insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO chat_questions (id,member_id,work_id,idempotency_key,input_hash,
                    question,generation,current_attempt_id)
                VALUES (?,1,?,'legacy-question','test-hash','합성 질문',1,?)
                """)) {
            insert.setObject(1, QUESTION);
            insert.setObject(2, WORK);
            insert.setObject(3, USER_RUN);
            insert.executeUpdate();
        }
        for (UUID run : new UUID[] {USER_RUN, SERVICE_RUN}) {
            boolean servicePaid = run.equals(SERVICE_RUN);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO chat_runs (id,question_id,work_id,generation,state,deadline,
                        usage_deadline,actor,policy,token_budget,allocated_tokens,call_count,tool_count)
                    VALUES (?,?,?,1,'RUNNING',now(),now(),?,?,10000,0,0,0)
                    """)) {
                insert.setObject(1, run);
                insert.setObject(2, servicePaid ? null : QUESTION);
                insert.setObject(3, WORK);
                insert.setString(4, servicePaid ? "test-backfill" : null);
                insert.setString(5, servicePaid ? "RETRIEVAL_INDEX_SERVICE_PAID_V1" : null);
                insert.executeUpdate();
            }
        }
    }

    private static void insertLegacyUsage(
            Connection connection, UUID id, UUID job, UUID run, String status,
            long reserved, Long input, Long output) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO ai_token_usages (request_id,member_id,work_id,analysis_job_id,chat_run_id,
                    purpose,attempt,model_name,status,reserved_tokens,input_tokens,cached_input_tokens,
                    output_tokens,created_at,updated_at)
                VALUES (?,1,?,?,?, ?,1,'gpt-6-luna',?,?,?,?,?,now(),now())
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, WORK);
            insert.setObject(3, job);
            insert.setObject(4, run);
            insert.setString(5, run == null ? "SETTING_EXTRACTION"
                    : run.equals(SERVICE_RUN) ? "OPERATIONAL_BACKFILL" : "CHAT_ANSWER");
            insert.setString(6, status);
            insert.setLong(7, reserved);
            insert.setObject(8, input);
            insert.setObject(9, input == null ? null : 3L);
            insert.setObject(10, output);
            insert.executeUpdate();
        }
    }

    private static void assertAccountUnchanged(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT * FROM ai_token_accounts WHERE member_id=1")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBigDecimal("granted_tokens")).isEqualByComparingTo("4000000");
            assertThat(row.getBigDecimal("used_tokens")).isEqualByComparingTo("1234567");
            assertThat(row.getBigDecimal("reserved_tokens")).isEqualByComparingTo("602");
        }
    }

    private static void assertUsage(
            Connection connection, UUID id, String policy, long reservedTokens, String reservedCharge,
            String charged, Long input, Long output) throws SQLException {
        try (var select = connection.prepareStatement("SELECT * FROM ai_token_usages WHERE request_id=?")) {
            select.setObject(1, id);
            try (var row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("charge_policy_version")).isEqualTo(policy);
                assertThat(row.getInt("charge_denominator")).isEqualTo(1);
                assertThat(row.getLong("reserved_tokens")).isEqualTo(reservedTokens);
                assertThat(row.getBigDecimal("reserved_charge")).isEqualByComparingTo(reservedCharge);
                if (charged == null) {
                    assertThat(row.getBigDecimal("charged_tokens")).isNull();
                } else {
                    assertThat(row.getBigDecimal("charged_tokens")).isEqualByComparingTo(charged);
                }
                assertThat(row.getObject("input_tokens", Long.class)).isEqualTo(input);
                assertThat(row.getObject("output_tokens", Long.class)).isEqualTo(output);
                assertThat(row.getObject("cached_input_tokens", Long.class))
                        .isEqualTo(input == null ? null : 3L);
                assertThat(row.getString("model_name")).isEqualTo("gpt-6-luna");
            }
        }
    }

    private static void assertColumnTypes(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var columns = statement.executeQuery("""
                        SELECT column_name,data_type,numeric_precision,numeric_scale
                        FROM information_schema.columns WHERE table_schema='public'
                          AND table_name='ai_token_accounts' AND column_name LIKE '%_tokens'
                        """)) {
            int count = 0;
            while (columns.next()) {
                assertThat(columns.getString("data_type")).isEqualTo("numeric");
                assertThat(columns.getInt("numeric_precision")).isEqualTo(22);
                assertThat(columns.getInt("numeric_scale")).isEqualTo(2);
                count++;
            }
            assertThat(count).isEqualTo(3);
        }
        try (var statement = connection.createStatement();
                var columns = statement.executeQuery("""
                        SELECT column_name,data_type FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='ai_token_usages'
                          AND column_name IN ('reserved_tokens','input_tokens','cached_input_tokens','output_tokens')
                        """)) {
            int count = 0;
            while (columns.next()) {
                assertThat(columns.getString("data_type")).isEqualTo("bigint");
                count++;
            }
            assertThat(count).isEqualTo(4);
        }
    }

    private static void assertInvalidChargesRejected(Connection connection, UUID id) throws SQLException {
        for (String assignment : new String[] {
            "charge_denominator=0", "charge_denominator=21", "reserved_charge=-0.05", "charged_tokens=-0.05"
        }) {
            try (var update = connection.prepareStatement(
                    "UPDATE ai_token_usages SET " + assignment + " WHERE request_id=?")) {
                update.setObject(1, id);
                assertThatThrownBy(update::executeUpdate)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                error -> assertThat(error.getSQLState()).isEqualTo("23514"));
            }
        }
    }
}
