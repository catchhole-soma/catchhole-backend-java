package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisFailureNotificationRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.processor.SettingBookAutomaticEvidenceValidator;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.processor.CharacterCurrentSupportingEvidenceResolver;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
    @PersistenceContext EntityManager em;
    UUID work;
    UUID episode;
    UUID job;
    final JsonMapper json = new JsonMapper();

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

    @Test
    @DisplayName("실패 진단과 실행 종류는 실패와 원자 저장하고 재시도 뒤 현재 진단으로 덮지 않는다")
    void snapshotsJobDiagnosticsBeforeRetryAndRefailure() {
        String first = diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", 409);
        jdbc.update("UPDATE analysis_jobs SET status='FAILED', failure_code='COMPARISON_VALIDATION_FAILED', failure_diagnostics=?::jsonb WHERE id=?", first, job);
        JsonNode event = stored("ANALYSIS_JOB");
        assertThat(event.path("failure_diagnostics").path("reasonCode").asString()).isEqualTo("CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH");
        assertThat(event.path("execution_job_id").asString()).isEqualTo(job.toString());
        assertThat(event.path("source_analysis_job_id").asString()).isEqualTo(job.toString());
        assertThat(event.path("job_type").asString()).isEqualTo("SETTING_EXTRACTION");
        jdbc.update("UPDATE analysis_jobs SET status='RUNNING',failure_diagnostics=NULL WHERE id=?", job);
        assertThat(stored("ANALYSIS_JOB")).isEqualTo(event);
        jdbc.update("UPDATE analysis_jobs SET status='FAILED', failure_diagnostics=?::jsonb WHERE id=?",
                diagnostics("FINALIZE", "INTERNAL_API_HTTP_ERROR", 500), job);
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE failure_diagnostics->>'reasonCode'='CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH'", Integer.class)).isEqualTo(1);
        var delayed = notifications.claimNext().orElseThrow();
        assertThat(delayed.id().toString()).isEqualTo(event.path("id").asString());
        assertThat(delayed.failureDiagnostics().reasonCode()).isEqualTo("CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH");
        assertThat(delayed.executionJobId()).isEqualTo(job);
        assertThat(delayed.sourceAnalysisJobId()).isEqualTo(job);
        assertThat(delayed.jobType()).isEqualTo("SETTING_EXTRACTION");
        assertThat(delayed.targetEpisodeNos()).containsExactly(3);
    }

    @Test
    @DisplayName("같은 실행·묶음·원인 후보만 합치며 원본 추출과 원인 근거 Job을 별도로 보존한다")
    void groupsCoherentCandidateDiagnosticsAndKeepsDifferentJobRoles() {
        UUID execution = UUID.randomUUID(), batch = UUID.randomUUID(), causeJob = UUID.randomUUID();
        insertJob(execution, "CHARACTER_FACT_COMPARISON");
        insertJob(causeJob, "SETTING_EXTRACTION");
        String diagnostic = ((ObjectNode) json.readTree(diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", 409)))
                .put("sourceAnalysisJobId", causeJob.toString()).toString();
        UUID first = insertCharacter("PROCESSING"), second = insertCharacter("PROCESSING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            failCharacter(first, execution, batch, diagnostic);
            failCharacter(second, execution, batch, diagnostic);
        });
        JsonNode event = stored("CHARACTER_COMPARISON");
        assertThat(event.path("failure_count").intValue()).isEqualTo(2);
        assertThat(event.path("analysis_job_id").asString()).isEqualTo(job.toString());
        assertThat(event.path("execution_job_id").asString()).isEqualTo(execution.toString());
        assertThat(event.path("source_analysis_job_id").asString()).isEqualTo(job.toString());
        assertThat(event.path("failure_diagnostics").path("sourceAnalysisJobId").asString()).isEqualTo(causeJob.toString());
        assertThat(event.path("comparison_batch_id").asString()).isEqualTo(batch.toString());
        assertThat(event.path("job_type").asString()).isEqualTo("CHARACTER_FACT_COMPARISON");
        // 보낸 시점의 FK로 과거 원인을 보충하지 않는다. 삭제된 원인도 실패 당시 snapshot을 유지한다.
        jdbc.update("DELETE FROM analysis_jobs WHERE id=?", causeJob);
        jdbc.update("UPDATE setting_candidates SET comparison_status='PENDING',comparison_failure_diagnostics=NULL,comparison_failure_analysis_job_id=NULL,comparison_failure_batch_id=NULL");
        assertThat(stored("CHARACTER_COMPARISON")).isEqualTo(event);
        var delayed = notifications.claimNext().orElseThrow();
        assertThat(delayed.executionJobId()).isEqualTo(execution);
        assertThat(delayed.sourceAnalysisJobId()).isEqualTo(job);
        assertThat(delayed.failureDiagnostics().sourceAnalysisJobId()).isEqualTo(causeJob);
        assertThat(delayed.comparisonBatchId()).isEqualTo(batch);
        assertThat(delayed.jobType()).isEqualTo("CHARACTER_FACT_COMPARISON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARACTER_FACT_COMPARISON", "WORLD_SETTING_COMPARISON"})
    @DisplayName("후보 사건과 실행 사건은 같은 실행 ID로 연결해도 개수와 종류를 합치지 않는다")
    void linksCandidateAndExecutionEventsWithoutMixingTheirCounts(String type) {
        UUID execution = UUID.randomUUID(), batch = UUID.randomUUID();
        insertJob(execution, type);
        boolean character = type.equals("CHARACTER_FACT_COMPARISON");
        if (character) {
            jdbc.update("INSERT INTO character_fact_comparison_batches(id,work_id,analysis_job_id,canonical_fact_type,status,candidate_count,base_snapshot_version,created_at,updated_at) VALUES (?,?,?,'PROFILE','PROCESSING',2,0,now(),now())", batch, work, execution);
        } else {
            jdbc.update("INSERT INTO world_setting_comparison_batches(id,work_id,source_episode_id,analysis_job_id,category,subject_resolution_type,canonical_subject_key,canonical_subject_name,resolved_target_world_setting_ids,status,candidate_count,created_at,updated_at) VALUES (?,?,?,?,'LOCATION','NEW','synthetic','합성','[]','PROCESSING',2,now(),now())", batch, work, episode, execution);
        }
        UUID first = character ? insertCharacter("PROCESSING") : insertWorld("PROCESSING");
        UUID second = character ? insertCharacter("PROCESSING") : insertWorld("PROCESSING");
        if (character) {
            jdbc.update("UPDATE setting_candidates SET character_comparison_batch_id=?,character_comparison_candidate_ref='C1' WHERE id=?", batch, first);
            jdbc.update("UPDATE setting_candidates SET character_comparison_batch_id=?,character_comparison_candidate_ref='C2' WHERE id=?", batch, second);
        } else {
            jdbc.update("UPDATE world_setting_candidates SET comparison_batch_id=?,comparison_candidate_ref='C1' WHERE id=?", batch, first);
            jdbc.update("UPDATE world_setting_candidates SET comparison_batch_id=?,comparison_candidate_ref='C2' WHERE id=?", batch, second);
        }
        String diagnostic = diagnostics(character ? "CHARACTER_COMPARE" : "WORLD_COMPARE", "LLM_TIMEOUT", null);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (character) { failCharacter(first, execution, batch, diagnostic); failCharacter(second, execution, batch, diagnostic); }
            else { failWorld(first, execution, batch, diagnostic); failWorld(second, execution, batch, diagnostic); }
            jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_diagnostics=?::jsonb WHERE id=?", diagnostic, execution);
        });
        assertThat(count()).isEqualTo(2);
        JsonNode candidateEvent = stored(character ? "CHARACTER_COMPARISON" : "WORLD_COMPARISON");
        JsonNode jobEvent = stored("ANALYSIS_JOB");
        assertThat(candidateEvent.path("failure_count").intValue()).isEqualTo(2);
        assertThat(jobEvent.path("failure_count").intValue()).isEqualTo(1);
        assertThat(candidateEvent.path("execution_job_id").asString()).isEqualTo(execution.toString());
        assertThat(jobEvent.path("execution_job_id").asString()).isEqualTo(execution.toString());
        assertThat(jobEvent.path("source_analysis_job_id").asString()).isEqualTo(job.toString());
        assertThat(jobEvent.path("comparison_batch_id").asString()).isEqualTo(batch.toString());
    }

    @Test
    @DisplayName("migration 이전 row는 현재 Job에 진단이 생겨도 발송 시 원인을 보충하지 않는다")
    void claimsLegacyRowWithoutEnrichingFromCurrentJob() {
        jdbc.update("INSERT INTO analysis_failure_notifications(work_id,analysis_job_id,episode_id,episode_no,failure_kind,failure_code,source_transaction) VALUES (?,?,?,3,'CHARACTER_COMPARISON','UNEXPECTED_ERROR',txid_current())", work, job, episode);
        jdbc.update("UPDATE analysis_jobs SET failure_diagnostics=?::jsonb WHERE id=?", diagnostics("FINALIZE", "INTERNAL_API_HTTP_ERROR", 500), job);
        var legacy = notifications.claimNext().orElseThrow();
        assertThat(legacy.jobId()).isEqualTo(job);
        assertThat(legacy.failureDiagnostics()).isNull();
        assertThat(legacy.executionJobId()).isNull();
        assertThat(legacy.sourceAnalysisJobId()).isNull();
        assertThat(legacy.comparisonBatchId()).isNull();
        assertThat(legacy.jobType()).isNull();
        assertThat(legacy.targetEpisodeNos()).isNull();
    }

    @Test
    @DisplayName("같은 트랜잭션에서도 reason·stage·실행·묶음이 다르면 원인을 하나로 대표하지 않는다")
    void separatesReasonsStagesExecutionsAndBatches() {
        UUID execution = UUID.randomUUID(), otherExecution = UUID.randomUUID(), batch = UUID.randomUUID();
        insertJob(execution, "CHARACTER_FACT_COMPARISON");
        insertJob(otherExecution, "CHARACTER_FACT_COMPARISON");
        UUID[] candidates = new UUID[5];
        for (int i = 0; i < candidates.length; i++) candidates[i] = insertCharacter("PROCESSING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            failCharacter(candidates[0], execution, batch, diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", 409));
            failCharacter(candidates[1], execution, batch, diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_OFFSET_INVALID", 409));
            failCharacter(candidates[2], execution, batch, diagnostics("CHARACTER_COMPARE", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", 409));
            failCharacter(candidates[3], otherExecution, batch, diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", 409));
            failCharacter(candidates[4], execution, UUID.randomUUID(), diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_SOURCE_HASH_MISMATCH", 409));
        });
        assertThat(count()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT failure_count FROM analysis_failure_notifications", Integer.class)).containsOnly(1);
    }

    @Test
    @DisplayName("같은 reason도 HTTP·원천 코드·원인 출처가 다른 후보는 하나의 진단으로 합치지 않는다")
    void separatesHttpSourceCodesAndCausalSources() {
        UUID execution = UUID.randomUUID(), batch = UUID.randomUUID();
        insertJob(execution, "WORLD_SETTING_COMPARISON");
        String baseline = diagnostics("WORLD_CONTEXT", "INTERNAL_API_HTTP_ERROR", 409);
        var sourceCode = ((ObjectNode) json.readTree(baseline)).put("sourceErrorCode", "AUTH_FORBIDDEN");
        var cause = ((ObjectNode) json.readTree(baseline)).put("sourceAnalysisJobId", job.toString())
                .put("sourceEpisodeId", episode.toString()).put("sourceEpisodeNo", 3);
        var anotherCause = cause.deepCopy().put("sourceCandidateId", insertWorld("PROCESSING").toString());
        UUID[] candidates = new UUID[5];
        for (int i = 0; i < candidates.length; i++) candidates[i] = insertWorld("PROCESSING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            failWorld(candidates[0], execution, batch, baseline);
            failWorld(candidates[1], execution, batch, diagnostics("WORLD_CONTEXT", "INTERNAL_API_HTTP_ERROR", 500));
            failWorld(candidates[2], execution, batch, sourceCode.toString());
            failWorld(candidates[3], execution, batch, cause.toString());
            failWorld(candidates[4], execution, batch, anotherCause.toString());
        });
        assertThat(count()).isEqualTo(5);
    }

    @Test
    @DisplayName("부가 진단이 없는 구 Worker도 서버가 안 실행·묶음은 유지하고 완전한 구 writer의 그룹 의미는 유지한다")
    void preservesKnownExecutionWithoutDiagnosticsAndLegacyGrouping() {
        UUID execution = UUID.randomUUID(), otherExecution = UUID.randomUUID(), batch = UUID.randomUUID();
        insertJob(execution, "CHARACTER_FACT_COMPARISON");
        insertJob(otherExecution, "CHARACTER_FACT_COMPARISON");
        UUID first = insertCharacter("PROCESSING"), second = insertCharacter("PROCESSING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insertCharacter("FAILED"); insertCharacter("FAILED");
            failCharacter(first, execution, batch, null);
            failCharacter(second, otherExecution, batch, null);
        });
        assertThat(count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT failure_count FROM analysis_failure_notifications WHERE execution_job_id IS NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE execution_job_id IS NOT NULL AND failure_diagnostics IS NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("다회차 비교 실행은 seed 회차 대신 실패 당시 실제 묶음의 전체 대상 회차를 고정한다")
    void snapshotsMultiEpisodeExecutionRange() {
        UUID execution = UUID.randomUUID(), batch = UUID.randomUUID(), nextEpisode = UUID.randomUUID(), nextJob = UUID.randomUUID();
        insertJob(execution, "CHARACTER_FACT_COMPARISON");
        jdbc.update("INSERT INTO episodes(id,work_id,episode_no,char_count,status,content_updated_at,created_at,updated_at) VALUES (?,?,4,100,'ANALYZING',now(),now(),now())", nextEpisode, work);
        jdbc.update("INSERT INTO analysis_jobs(id,work_id,episode_id,source_episode_no,job_type,status,created_at,updated_at) VALUES (?,?,?,4,'SETTING_EXTRACTION','SUCCEEDED',now(),now())", nextJob, work, nextEpisode);
        jdbc.update("INSERT INTO character_fact_comparison_batches(id,work_id,analysis_job_id,canonical_fact_type,status,candidate_count,base_snapshot_version,created_at,updated_at) VALUES (?,?,?,'PROFILE','PROCESSING',2,0,now(),now())", batch, work, execution);
        UUID first = insertCharacter("PROCESSING"), second = insertCharacter("PROCESSING");
        jdbc.update("UPDATE setting_candidates SET character_comparison_batch_id=?,character_comparison_candidate_ref='C1' WHERE id=?", batch, first);
        jdbc.update("UPDATE setting_candidates SET character_comparison_batch_id=?,character_comparison_candidate_ref='C2',episode_id=?,analysis_job_id=? WHERE id=?", batch, nextEpisode, nextJob, second);
        jdbc.update("UPDATE analysis_jobs SET setting_candidate_id=?,character_comparison_input_hash=? WHERE id=?", first, "a".repeat(64), execution);
        jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_code='LLM_NETWORK_ERROR',failure_diagnostics=?::jsonb WHERE id=?",
                diagnostics("CHARACTER_COMPARE", "LLM_TIMEOUT", null), execution);
        JsonNode event = stored("ANALYSIS_JOB");
        assertThat(event.path("target_episode_nos").toString()).isEqualTo("[3,4]");
        assertThat(event.path("source_analysis_job_id").isNull()).isTrue();
        assertThat(event.path("comparison_batch_id").asString()).isEqualTo(batch.toString());
        assertThat(event.path("failure_count").intValue()).isEqualTo(1);
        jdbc.update("DELETE FROM character_fact_comparison_batches WHERE id=?", batch);
        assertThat(stored("ANALYSIS_JOB")).isEqualTo(event);
    }

    @Test
    @DisplayName("기존 batch-wide 추출 Job도 기록 seed 대신 저장된 실제 target 회차를 고정한다")
    void snapshotsLegacyMultiEpisodeJobTargets() {
        UUID otherEpisode = UUID.randomUUID();
        jdbc.update("INSERT INTO episodes(id,work_id,episode_no,char_count,status,content_updated_at,created_at,updated_at) VALUES (?,?,5,100,'ANALYZING',now(),now(),now())", otherEpisode, work);
        jdbc.update("INSERT INTO analysis_job_episode_targets(analysis_job_id,episode_id) VALUES (?,?),(?,?)", job, episode, job, otherEpisode);
        failJob();
        assertThat(stored("ANALYSIS_JOB").path("target_episode_nos").toString()).isEqualTo("[3,5]");
    }

    @Test
    @DisplayName("원고·연락처·URL·인증·예외·mention과 잘못된 부가 필드는 outbox에 복사하지 않는다")
    void snapshotsOnlySafeBoundedDiagnosticFields() {
        String unsafe = "PRIVATE_MANUSCRIPT writer@example.test https://private.test Bearer SECRET @everyone";
        var diagnostic = ((ObjectNode) json.readTree(diagnostics("FINALIZE", "INTERNAL_API_HTTP_ERROR", 500)))
                .put("errorMessage", unsafe).put("stackTrace", unsafe).put("requestId", unsafe)
                .put("traceId", unsafe).put("sourceCandidateId", unsafe).put("sourceErrorCode", unsafe);
        jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_diagnostics=?::jsonb,error_message=? WHERE id=?", diagnostic.toString(), unsafe, job);
        JsonNode event = stored("ANALYSIS_JOB");
        assertThat(event.toString()).doesNotContain("PRIVATE_MANUSCRIPT", "writer@", "private.test", "SECRET", "@everyone", "stackTrace", "errorMessage");
        assertThat(event.path("failure_diagnostics").path("httpStatus").asInt(-1)).isEqualTo(500);
        assertThat(event.path("failure_diagnostics").path("sourceErrorCode").asString()).isEqualTo("UNKNOWN");
        assertThat(event.path("failure_diagnostics").path("requestId").isMissingNode() || event.path("failure_diagnostics").path("requestId").isNull()).isTrue();
    }

    @Test
    @DisplayName("SQL writer의 알 수 없는 작업 종류도 자유 텍스트로 outbox에 복사하지 않는다")
    void unknownJobTypeDoesNotCopyFreeTextIntoSnapshot() {
        jdbc.update("UPDATE analysis_jobs SET job_type='raw@example.test @everyone',status='FAILED' WHERE id=?", job);
        JsonNode event = stored("ANALYSIS_JOB");
        assertThat(event.path("job_type").isNull()).isTrue();
        assertThat(event.path("source_analysis_job_id").isNull()).isTrue();
        assertThat(event.toString()).doesNotContain("raw@", "@everyone");
    }

    @Test
    @DisplayName("검수 실행은 알려진 종류·대상 회차를 보존하되 원본 추출 Job으로 표시하지 않는다")
    void validationJobIsNotItsOwnOriginalExtraction() {
        jdbc.update("UPDATE analysis_jobs SET job_type='EPISODE_VALIDATION',status='FAILED' WHERE id=?", job);
        JsonNode event = stored("ANALYSIS_JOB");
        assertThat(event.path("job_type").asString()).isEqualTo("EPISODE_VALIDATION");
        assertThat(event.path("execution_job_id").asString()).isEqualTo(job.toString());
        assertThat(event.path("source_analysis_job_id").isNull()).isTrue();
        assertThat(event.path("target_episode_nos").toString()).isEqualTo("[3]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "42", "{}", "{\"schemaVersion\":2,\"origin\":\"SPRING\"}",
            "{\"schemaVersion\":\"1\",\"origin\":\"SPRING\"}", "{\"schemaVersion\":1,\"origin\":\"PRIVATE_TEXT\"}"})
    @DisplayName("잘못되거나 미지원인 부가 진단은 버려도 본래 실패와 알림은 저장한다")
    void malformedOptionalDiagnosticsDoNotRejectFailure(String diagnostic) {
        jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_diagnostics=?::jsonb WHERE id=?", diagnostic, job);
        assertThat(count()).isEqualTo(1);
        assertThat(stored("ANALYSIS_JOB").path("failure_diagnostics").isNull()).isTrue();
    }

    @Test
    @DisplayName("숫자나 문자열로 위장한 부가 식별자·HTTP는 실제 관측값으로 복사하지 않는다")
    void ignoresWrongJsonTypesForOptionalObservedMetadata() {
        var diagnostic = ((ObjectNode) json.readTree(diagnostics("EXTRACTION", "LLM_RATE_LIMIT", null)))
                .put("origin", "PROVIDER").put("requestId", 123456).put("httpStatus", "429")
                .put("sourceEpisodeNo", "3");
        diagnostic.set("traceId", json.readTree("12345678901234567890123456789012"));
        jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_diagnostics=?::jsonb WHERE id=?", diagnostic.toString(), job);
        JsonNode snapshot = stored("ANALYSIS_JOB").path("failure_diagnostics");
        assertThat(snapshot.has("requestId")).isFalse();
        assertThat(snapshot.has("traceId")).isFalse();
        assertThat(snapshot.has("httpStatus")).isFalse();
        assertThat(snapshot.has("sourceEpisodeNo")).isFalse();
    }

    @Test
    @DisplayName("진단이 있는 후보 실패도 rollback 시 상태·진단·알림을 함께 되돌린다")
    void rollsBackCandidateDiagnosticsAndNotificationTogether() {
        UUID candidate = insertCharacter("PROCESSING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            failCharacter(candidate, job, null, diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_OFFSET_INVALID", 409));
            assertThat(count()).isEqualTo(1);
            assertThat(stored("CHARACTER_COMPARISON").path("failure_diagnostics").path("reasonCode").asString()).isEqualTo("CURRENT_EVIDENCE_OFFSET_INVALID");
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT comparison_status FROM setting_candidates WHERE id=?", String.class, candidate)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT comparison_failure_diagnostics IS NULL FROM setting_candidates WHERE id=?", Boolean.class, candidate)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING"})
    @DisplayName("구 SQL writer가 Job을 재시도해도 이전 진단을 지우고 과거 outbox만 보존한다")
    void legacyJobRetryCannotReusePreviousAttemptDiagnostics(String activeStatus) {
        jdbc.update("UPDATE analysis_jobs SET status='FAILED',failure_diagnostics=?::jsonb WHERE id=?",
                diagnostics("EXTRACTION", "LLM_TIMEOUT", null), job);
        JsonNode first = stored("ANALYSIS_JOB");
        jdbc.update("UPDATE analysis_jobs SET status=? WHERE id=?", activeStatus, job);
        assertThat(jdbc.queryForObject("SELECT failure_diagnostics IS NULL FROM analysis_jobs WHERE id=?", Boolean.class, job)).isTrue();
        failJob();
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE failure_diagnostics IS NULL", Integer.class)).isEqualTo(1);
        assertThat(json.readTree(jdbc.queryForObject("SELECT to_jsonb(n)::text FROM analysis_failure_notifications n WHERE id=?", String.class,
                UUID.fromString(first.path("id").asString())))).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"setting_candidates", "world_setting_candidates"})
    @DisplayName("구 SQL writer의 후보 재시도는 새 실행을 모를 때 과거 실행·묶음·진단을 재사용하지 않는다")
    void legacyCandidateRetryCannotReusePreviousAttemptLinkage(String table) {
        UUID execution = UUID.randomUUID(), batch = UUID.randomUUID();
        insertJob(execution, "CHARACTER_FACT_COMPARISON");
        boolean character = table.equals("setting_candidates");
        UUID candidate = character ? insertCharacter("PROCESSING") : insertWorld("PROCESSING");
        String diagnostic = diagnostics("CHARACTER_CONTEXT", "CURRENT_EVIDENCE_OFFSET_INVALID", 409);
        if (character) failCharacter(candidate, execution, batch, diagnostic);
        else failWorld(candidate, execution, batch, diagnostic);
        JsonNode first = stored(character ? "CHARACTER_COMPARISON" : "WORLD_COMPARISON");
        jdbc.update("UPDATE " + table + " SET comparison_status='PENDING' WHERE id=?", candidate);
        assertThat(jdbc.queryForObject("SELECT comparison_failure_diagnostics IS NULL AND comparison_failure_analysis_job_id IS NULL AND comparison_failure_batch_id IS NULL FROM " + table + " WHERE id=?", Boolean.class, candidate)).isTrue();
        jdbc.update("UPDATE " + table + " SET comparison_status='FAILED' WHERE id=?", candidate);
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_failure_notifications WHERE failure_diagnostics IS NULL AND execution_job_id IS NULL AND comparison_batch_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(json.readTree(jdbc.queryForObject("SELECT to_jsonb(n)::text FROM analysis_failure_notifications n WHERE id=?", String.class,
                UUID.fromString(first.path("id").asString())))).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"character_fact_comparison_batches", "world_setting_comparison_batches"})
    @DisplayName("구 SQL writer의 묶음 재시도도 이전 시도 진단을 지운다")
    void legacyBatchRetryClearsPreviousAttemptDiagnostics(String table) {
        UUID batch = UUID.randomUUID();
        if (table.equals("character_fact_comparison_batches")) {
            jdbc.update("INSERT INTO character_fact_comparison_batches(id,work_id,analysis_job_id,canonical_fact_type,status,candidate_count,base_snapshot_version,created_at,updated_at) VALUES (?,?,?,'PROFILE','FAILED',1,0,now(),now())", batch, work, job);
        } else {
            jdbc.update("INSERT INTO world_setting_comparison_batches(id,work_id,source_episode_id,analysis_job_id,category,subject_resolution_type,canonical_subject_key,canonical_subject_name,resolved_target_world_setting_ids,status,candidate_count,created_at,updated_at) VALUES (?,?,?,?,'LOCATION','NEW','synthetic','합성','[]','FAILED',1,now(),now())", batch, work, episode, job);
        }
        jdbc.update("UPDATE " + table + " SET failure_diagnostics=?::jsonb WHERE id=?", diagnostics("WORLD_COMPARE", "LLM_TIMEOUT", null), batch);
        jdbc.update("UPDATE " + table + " SET status='PROCESSING' WHERE id=?", batch);
        assertThat(jdbc.queryForObject("SELECT failure_diagnostics IS NULL FROM " + table + " WHERE id=?", Boolean.class, batch)).isTrue();
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("EPISODE의 유지된 인용 확인 불가는 정상 REVIEW_REQUIRED 저장이며 PostgreSQL 실패 알림을 만들지 않는다")
    void retainedUnavailableEpisodeReviewDoesNotEmitFailure() {
        UUID retained = insertCharacter("COMPLETED"), incoming = insertCharacter("PROCESSING");
        jdbc.update("UPDATE analysis_jobs SET source_content_hash=?,source_content_s3_key='synthetic/retained-source' WHERE id=?", "b".repeat(64), job);
        jdbc.update("UPDATE setting_candidates SET review_status='CONFIRMED',evidence_spans='[{\"quote\":\"원문에 존재하지 않는 합성 인용\"}]'::jsonb WHERE id=?", retained);
        var storage = mock(ObjectStorageService.class);
        when(storage.getText("synthetic/retained-source")).thenReturn("합성 원문의 현재 인용");
        when(storage.contentHash("합성 원문의 현재 인용")).thenReturn("b".repeat(64));
        var resolver = new CharacterCurrentSupportingEvidenceResolver(storage, new SettingBookAutomaticEvidenceValidator(storage), jdbc);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var previous = em.find(SettingCandidate.class, retained);
            var source = resolver.captureRetainedCandidate(resolver.begin(previous.getWork()), previous);
            assertThat(source.path("availability").asText()).isEqualTo("UNAVAILABLE");
            assertThat(source.path("unavailableReason").asText()).isEqualTo("QUOTE_NOT_FOUND");
            assertThat(source.path("evidenceSpans")).isEmpty();
            var candidate = em.find(SettingCandidate.class, incoming);
            candidate.completeComparison(CharacterFactOperation.REVIEW_REQUIRED, null, null, null, null, null,
                    CharacterFactTemporalScope.PRESENT, "합성 검토 대기", null, LocalDateTime.now());
            em.flush();
        });
        assertThat(jdbc.queryForObject("SELECT comparison_status FROM setting_candidates WHERE id=?", String.class, incoming)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT suggested_operation FROM setting_candidates WHERE id=?", String.class, incoming)).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT comparison_failure_code IS NULL FROM setting_candidates WHERE id=?", Boolean.class, incoming)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status='RUNNING' AND failure_code IS NULL AND source_kind='EPISODE' FROM analysis_jobs WHERE id=?", Boolean.class, job)).isTrue();
        assertThat(jdbc.queryForObject("SELECT review_status FROM setting_candidates WHERE id=?", String.class, retained)).isEqualTo("CONFIRMED");
        assertThat(count()).isZero();
    }

    private String diagnostics(String stage, String reason, Integer httpStatus) {
        var value = json.createObjectNode().put("schemaVersion", 1).put("origin", "SPRING")
                .put("stage", stage).put("reasonCode", reason).put("sourceErrorCode", "COMMON_INTERNAL_SERVER_ERROR")
                .put("requestId", "11111111-2222-3333-4444-555555555555").put("traceId", "0123456789abcdef0123456789abcdef");
        if (httpStatus != null) value.put("httpStatus", httpStatus);
        return value.toString();
    }

    private void failCharacter(UUID candidate, UUID execution, UUID batch, String diagnostics) {
        jdbc.update("UPDATE setting_candidates SET comparison_status='FAILED',comparison_failure_diagnostics=?::jsonb,comparison_failure_analysis_job_id=?,comparison_failure_batch_id=? WHERE id=?", diagnostics, execution, batch, candidate);
    }

    private void failWorld(UUID candidate, UUID execution, UUID batch, String diagnostics) {
        jdbc.update("UPDATE world_setting_candidates SET comparison_status='FAILED',comparison_failure_diagnostics=?::jsonb,comparison_failure_analysis_job_id=?,comparison_failure_batch_id=? WHERE id=?", diagnostics, execution, batch, candidate);
    }

    private JsonNode stored(String kind) {
        return json.readTree(jdbc.queryForObject("SELECT to_jsonb(n)::text FROM analysis_failure_notifications n WHERE failure_kind=?", String.class, kind));
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
                VALUES (?,?,?,?,'CHARACTER','합성 인물','UNRESOLVED','상태','STRING','PENDING_REVIEW',?,'LLM_RESPONSE_PARSE_ERROR',now(),now())
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
