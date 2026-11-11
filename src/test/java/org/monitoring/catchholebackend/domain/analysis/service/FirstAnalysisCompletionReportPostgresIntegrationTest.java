package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.support.PostgresFixtureCleanup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 첫 분석 완료 보고 이관과 판정")
class FirstAnalysisCompletionReportPostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "first_report_test").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "first_report_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> url("first_report_test"));
        p.add("spring.datasource.username", () -> "first_report_test");
        p.add("spring.datasource.password", () -> "test-only");
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.flyway.enabled", () -> "true");
        p.add("spring.docker.compose.enabled", () -> "false");
        p.add("spring.config.import", () -> "");
    }

    static final LocalDateTime EARLIEST = LocalDateTime.of(2026, 10, 1, 10, 0);
    static final LocalDateTime LATER = LocalDateTime.of(2026, 10, 5, 10, 0);
    static final LocalDateTime MANUAL_SEALED = LocalDateTime.of(2026, 10, 2, 9, 30);
    static final LocalDateTime AUTO_APPLIED = LocalDateTime.of(2026, 10, 3, 9, 0);
    static final LocalDateTime REVIEW_HELD = LocalDateTime.of(2026, 10, 4, 8, 0);
    static final LocalDateTime UPDATED_ONLY = LocalDateTime.of(2026, 10, 6, 7, 0);

    @Autowired JdbcTemplate jdbc;
    @Autowired FirstAnalysisCompletionReportService reports;

    @BeforeEach
    void clean() {
        PostgresFixtureCleanup.truncate(jdbc, "TRUNCATE TABLE works, members CASCADE");
    }

    @Test @DisplayName("V122에서 올릴 때 이미 첫 분석을 완료한 회원만 가장 이른 완료 시각으로 보고 완료 처리한다")
    void migratesExistingCompletions() {
        jdbc.execute("CREATE DATABASE first_report_legacy");
        String legacyUrl = url("first_report_legacy");
        Flyway.configure().dataSource(legacyUrl, "first_report_test", "test-only").target("122").load().migrate();
        var legacy = new JdbcTemplate(new DriverManagerDataSource(legacyUrl, "first_report_test", "test-only"));

        UUID single = work(legacy, 101);
        plainJob(legacy, single, "SETTING_EXTRACTION", "SUCCEEDED", LATER, LATER);
        plainJob(legacy, single, "SETTING_EXTRACTION", "SUCCEEDED", EARLIEST, EARLIEST);
        plainJob(legacy, work(legacy, 102), "SETTING_EXTRACTION", "PENDING", null, LATER);
        UUID manual = work(legacy, 103);
        orderedJob(legacy, manual, episode(legacy, manual), "MANUAL", "SEALED", null, null, MANUAL_SEALED);
        UUID notApplied = work(legacy, 104);
        orderedJob(legacy, notApplied, episode(legacy, notApplied), "AUTOMATIC", "SEALED", null, null, LATER);
        UUID applied = work(legacy, 105);
        orderedJob(legacy, applied, episode(legacy, applied), "AUTOMATIC", "SEALED", AUTO_APPLIED, null, AUTO_APPLIED);
        UUID incomplete = work(legacy, 106);
        orderedJob(legacy, incomplete, episode(legacy, incomplete), "AUTOMATIC", "INCOMPLETE", null, null, LATER);
        UUID held = work(legacy, 107);
        orderedJob(legacy, held, episode(legacy, held), "AUTOMATIC", "SEALED", null, REVIEW_HELD, REVIEW_HELD);
        plainJob(legacy, work(legacy, 108), "EPISODE_VALIDATION", "SUCCEEDED", LATER, LATER);
        member(legacy, 109);
        plainJob(legacy, work(legacy, 110), "SETTING_EXTRACTION", "SUCCEEDED", null, UPDATED_ONLY);

        Flyway.configure().dataSource(legacyUrl, "first_report_test", "test-only").load().migrate();

        Map<Long, LocalDateTime> reported = new HashMap<>();
        legacy.query("SELECT id, first_analysis_completion_reported_at FROM members", rs -> {
            Timestamp at = rs.getTimestamp(2);
            reported.put(rs.getLong(1), at == null ? null : at.toLocalDateTime());
        });
        assertThat(reported).hasSize(10)
                .containsEntry(101L, EARLIEST)
                .containsEntry(102L, null)
                .containsEntry(103L, MANUAL_SEALED)
                .containsEntry(104L, null)
                .containsEntry(105L, AUTO_APPLIED)
                .containsEntry(106L, null)
                .containsEntry(107L, REVIEW_HELD)
                .containsEntry(108L, null)
                .containsEntry(109L, null)
                .containsEntry(110L, UPDATED_ONLY);
    }

    @Test @DisplayName("PostgreSQL에서도 순차 자동 분석은 자동 반영까지 끝나야 보고 대상이며 선점은 한 번이다")
    void reportsOnlyAfterAutomaticApplicationOnPostgres() {
        UUID work = work(jdbc, 201);
        orderedJob(jdbc, work, episode(jdbc, work), "AUTOMATIC", "SEALED", null, null, LATER);
        assertThat(reports.getFirstAnalysisCompletionReport(201L).shouldReport()).isFalse();

        jdbc.update("UPDATE analysis_jobs SET automatic_applied_at = ? WHERE work_id = ?", AUTO_APPLIED, work);
        assertThat(reports.getFirstAnalysisCompletionReport(201L).shouldReport()).isTrue();
        assertThat(reports.claimFirstAnalysisCompletionReport(201L).shouldReport()).isTrue();
        assertThat(reports.claimFirstAnalysisCompletionReport(201L).shouldReport()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT first_analysis_completion_reported_at IS NOT NULL FROM members WHERE id = 201", Boolean.class)).isTrue();
    }

    static void member(JdbcTemplate db, long id) {
        db.update("""
                INSERT INTO members(id,email,password_hash,phone_verified,email_verified,display_name,status,role,created_at,updated_at)
                VALUES (?,?,'test-only',false,true,'합성 작가','ACTIVE','AUTHOR',now(),now())
                """, id, "first-report-" + id + "@example.test");
    }

    static UUID work(JdbcTemplate db, long memberId) {
        member(db, memberId);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO works(id,member_id,title,genre,latest_episode_no,created_at,updated_at) VALUES (?,?,'합성 작품','FANTASY',1,now(),now())",
                id, memberId);
        return id;
    }

    static UUID episode(JdbcTemplate db, UUID workId) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO episodes(id,work_id,episode_no,char_count,status,content_s3_key,content_hash,content_updated_at,created_at,updated_at)
                VALUES (?,?,1,10,'ANALYZED','synthetic/ep',repeat('a',64),now(),now(),now())
                """, id, workId);
        return id;
    }

    static void plainJob(JdbcTemplate db, UUID workId, String type, String status, LocalDateTime completedAt, LocalDateTime updatedAt) {
        db.update("INSERT INTO analysis_jobs(id,work_id,job_type,status,completed_at,created_at,updated_at) VALUES (?,?,?,?,?,now(),?)",
                UUID.randomUUID(), workId, type, status, completedAt, updatedAt);
    }

    // V44·V46·V49·V77·V99의 순차 실행 CHECK를 만족하는 첫 회차(run_sequence 0) 설정 추출 Job.
    static void orderedJob(JdbcTemplate db, UUID workId, UUID episodeId, String reviewMode, String journalStatus,
            LocalDateTime appliedAt, LocalDateTime heldAt, LocalDateTime completedAt) {
        db.update("""
                INSERT INTO analysis_jobs(id,work_id,episode_id,source_episode_no,job_type,status,analysis_mode,review_mode,
                  analysis_run_id,run_generation,run_sequence,run_base_state,source_content_s3_key,source_content_hash,
                  journal_status,input_state_hash,state_journal,automatic_applied_at,review_application_held_at,completed_at,created_at,updated_at)
                VALUES (?,?,?,1,'SETTING_EXTRACTION','SUCCEEDED','ORDERED_PROVISIONAL',?,?,1,0,'{}'::jsonb,'synthetic/ep',repeat('a',64),
                  ?,repeat('b',64),jsonb_build_object('changes','[]'::jsonb,'outputStateHash',repeat('c',64)),?,?,?,now(),now())
                """, UUID.randomUUID(), workId, episodeId, reviewMode, UUID.randomUUID(), journalStatus, appliedAt, heldAt, completedAt);
    }
}
