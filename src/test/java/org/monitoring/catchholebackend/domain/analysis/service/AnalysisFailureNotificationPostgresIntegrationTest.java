package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisFailureNotificationRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 회차 실패 알림 기록과 발송 선점")
class AnalysisFailureNotificationPostgresIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_USER", "notification_test").withEnv("POSTGRES_PASSWORD", "test-only")
            .withEnv("POSTGRES_DB", "notification_test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/notification_test");
        properties.add("spring.datasource.username", () -> "notification_test");
        properties.add("spring.datasource.password", () -> "test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
        properties.add("spring.config.import", () -> "");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisFailureNotificationRepository notifications;
    @Autowired AnalysisJobRepository analysisJobs;
    @Autowired PlatformTransactionManager transactions;
    UUID work;
    UUID episode;
    UUID job;

    @BeforeEach
    void seed() {
        // Testcontainers가 새로 만든 전용 DB만 정리한다.
        jdbc.execute("TRUNCATE TABLE works, members CASCADE");
        jdbc.update("""
                INSERT INTO members(id,email,password_hash,phone_verified,email_verified,display_name,status,role,created_at,updated_at)
                VALUES (1,'notification@example.test','test-only',false,true,'합성 작가','ACTIVE','AUTHOR',now(),now())
                """);
        work = UUID.randomUUID();
        episode = UUID.randomUUID();
        job = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO works(id,member_id,title,genre,latest_episode_no,created_at,updated_at)
                VALUES (?,1,'합성 작품','FANTASY',3,now(),now())
                """, work);
        jdbc.update("""
                INSERT INTO episodes(id,work_id,episode_no,char_count,status,content_updated_at,created_at,updated_at)
                VALUES (?,?,3,100,'ANALYZING',now(),now(),now())
                """, episode, work);
        insertJob(job, "SETTING_EXTRACTION");
    }

    @ParameterizedTest
    @EnumSource(AnalysisFailureCode.class)
    @DisplayName("토큰 부족·LLM 오류·lease 만료를 포함한 모든 실패 코드가 기록된다")
    void capturesEveryFailureCodeWithoutRawError(AnalysisFailureCode code) {
        jdbc.update("UPDATE analysis_jobs SET status='FAILED', failure_code=?, error_message=? WHERE id=?",
                code.name(), "private-source https://secret.example.test/token", job);
        var event = notifications.claimNext().orElseThrow();
        assertThat(event.failureCode()).isEqualTo(code.name());
        assertThat(event.memberId()).isEqualTo(1L);
        assertThat(event.episodeNo()).isEqualTo(3);
        assertThat(event.toString()).doesNotContain("private-source", "secret.example.test");
        assertThat(notifications.claimNext()).isEmpty();
    }

    @Test
    @DisplayName("실패 상태의 반복 저장은 중복하지 않고 재실행 후 실패는 새 알림으로 기록한다")
    void notifiesOncePerFailureTransition() {
        failJob();
        jdbc.update("UPDATE analysis_jobs SET status='FAILED', error_message='changed' WHERE id=?", job);
        assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE analysis_jobs SET status='RUNNING' WHERE id=?", job);
        failJob();
        assertThat(count()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패 저장 롤백은 알림도 롤백하며 선점자는 미커밋 알림을 읽지 않는다")
    void rollsBackWithFailureAndDoesNotExposeUncommittedEvents() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            failJob();
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(notifications::claimNext).get()).isEmpty();
            } catch (Exception ex) { throw new AssertionError(ex); }
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, job)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("원 Job 성공 여부와 무관하게 최초 저장 실패·캐릭터 및 세계관 부분 실패를 묶어 기록한다")
    void capturesPartialFailuresAndGroupsCandidatesInSameTransaction() {
        jdbc.update("UPDATE analysis_jobs SET status='SUCCEEDED' WHERE id=?", job);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insertCharacter("FAILED");
            insertCharacter("FAILED");
        });
        UUID world = insertWorld("PROCESSING");
        jdbc.update("UPDATE world_setting_candidates SET comparison_status='FAILED', comparison_failure_code='LLM_PROVIDER_ERROR' WHERE id=?", world);
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT failure_count FROM analysis_failure_notifications WHERE failure_kind='CHARACTER_COMPARISON'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM analysis_failure_notifications WHERE failure_kind='WORLD_COMPARISON'", String.class)).isEqualTo("LLM_PROVIDER_ERROR");
        jdbc.update("UPDATE world_setting_candidates SET comparison_status='PENDING' WHERE id=?", world);
        jdbc.update("UPDATE world_setting_candidates SET comparison_status='FAILED' WHERE id=?", world);
        assertThat(count()).isEqualTo(3);
    }

    @Test
    @DisplayName("취소·재대기·검토 대기에는 보내지 않고 숨김 재비교 Job의 실패도 기록한다")
    void ignoresNonFailuresAndIncludesHiddenComparisonJobs() {
        jdbc.update("UPDATE analysis_jobs SET status='PENDING' WHERE id=?", job);
        jdbc.update("UPDATE analysis_jobs SET status='CANCELED' WHERE id=?", job);
        insertCharacter("RECOMPARISON_REQUIRED");
        insertWorld("COMPLETED");
        assertThat(count()).isZero();
        UUID comparison = UUID.randomUUID();
        insertJob(comparison, "CHARACTER_FACT_COMPARISON");
        jdbc.update("UPDATE analysis_jobs SET status='FAILED', failure_code='WORKER_LEASE_EXPIRED' WHERE id=?", comparison);
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisJobType.class, names = {"CHARACTER_FACT_COMPARISON", "WORLD_SETTING_COMPARISON"})
    @DisplayName("새 분석으로 대체된 숨김 비교 Job은 JPA로 취소되어도 실패 알림을 만들지 않는다")
    void doesNotNotifySupersededComparisonJobs(AnalysisJobType type) {
        UUID comparison = UUID.randomUUID();
        insertJob(comparison, type.name());
        jdbc.update("UPDATE analysis_jobs SET lease_token=?, lease_expires_at=now()+interval '5 minutes' WHERE id=?",
                UUID.randomUUID(), comparison);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var comparisonJob = analysisJobs.findById(comparison).orElseThrow();
            comparisonJob.cancelForSourceChange("새 회차 분석 작업으로 대체되었습니다.");
            analysisJobs.flush();
        });

        assertThat(jdbc.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, comparison))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("""
                SELECT lease_token IS NULL AND lease_expires_at IS NULL AND failure_code IS NULL
                    AND completed_at IS NOT NULL FROM analysis_jobs WHERE id=?
                """, Boolean.class, comparison)).isTrue();
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("동시 발송자는 다른 알림을 선점하고 만료된 선점의 응답은 새 선점을 완료하지 못한다")
    void claimsConcurrentlyAndRejectsStaleAcknowledgement() throws Exception {
        failJob();
        insertWorld("FAILED");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(notifications::claimNext);
            var second = executor.submit(notifications::claimNext);
            assertThat(first.get().orElseThrow().id()).isNotEqualTo(second.get().orElseThrow().id());
        }
        jdbc.update("UPDATE analysis_failure_notifications SET available_at=now()-interval '1 minute'");
        var oldLease = notifications.claimNext().orElseThrow();
        jdbc.update("UPDATE analysis_failure_notifications SET available_at=now()-interval '1 minute' WHERE id=?", oldLease.id());
        // 다른 row는 처리 완료하여 같은 알림을 재선점한다.
        jdbc.update("UPDATE analysis_failure_notifications SET delivered_at=now() WHERE id<>?", oldLease.id());
        var newLease = notifications.claimNext().orElseThrow();
        assertThat(newLease.id()).isEqualTo(oldLease.id());
        assertThat(newLease.leaseToken()).isNotEqualTo(oldLease.leaseToken());
        notifications.markDelivered(oldLease);
        assertThat(jdbc.queryForObject("SELECT delivered_at IS NULL FROM analysis_failure_notifications WHERE id=?", Boolean.class, oldLease.id())).isTrue();
        notifications.markDelivered(newLease);
        assertThat(notifications.claimNext()).isEmpty();
    }

    @Test
    @DisplayName("전송 장애는 대기에 남고 재시도 이후에만 완료되며 오래된 발송 완료 이력만 정리한다")
    void retainsUndeliveredEventsAndCleansOnlyDeliveredHistory() {
        failJob();
        var event = notifications.claimNext().orElseThrow();
        notifications.postpone(event, Duration.ofMinutes(1));
        assertThat(notifications.claimNext()).isEmpty();
        notifications.deleteDeliveredHistory();
        assertThat(count()).isEqualTo(1);
        jdbc.update("UPDATE analysis_failure_notifications SET available_at=now()-interval '1 minute'");
        var retry = notifications.claimNext().orElseThrow();
        assertThat(retry.id()).isEqualTo(event.id());
        assertThat(retry.attempts()).isEqualTo(2);
        notifications.markDelivered(retry);
        assertThat(notifications.claimNext()).isEmpty();
        jdbc.update("UPDATE analysis_failure_notifications SET delivered_at=now()-interval '31 days'");
        notifications.deleteDeliveredHistory();
        assertThat(count()).isZero();
    }

    private void insertJob(UUID id, String kind) {
        jdbc.update("""
                INSERT INTO analysis_jobs(id,work_id,episode_id,source_episode_no,job_type,status,created_at,updated_at)
                VALUES (?,?,?,3,?,'RUNNING',now(),now())
                """, id, work, episode, kind);
    }

    private UUID insertCharacter(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO setting_candidates(id,work_id,episode_id,analysis_job_id,entity_type,entity_name,
                    match_status,attribute_name,value_type,review_status,comparison_status,comparison_failure_code,created_at,updated_at)
                VALUES (?,?,?,?,'CHARACTER','합성 인물','UNMATCHED','상태','TEXT','PENDING_REVIEW',?,'LLM_RESPONSE_PARSE_ERROR',now(),now())
                """, id, work, episode, job, status);
        return id;
    }

    private UUID insertWorld(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO world_setting_candidates(id,work_id,source_episode_id,analysis_job_id,category,
                    subject_name,setting_name,extracted_value,evidence_spans,comparison_status,created_at,updated_at)
                VALUES (?,?,?,?,'LOCATION','합성 지역','날씨','합성 값','[]',?,now(),now())
                """, id, work, episode, job, status);
        return id;
    }

    private void failJob() { jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_code='LLM_NETWORK_ERROR' WHERE id=?", job); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM analysis_failure_notifications", Integer.class); }
}
