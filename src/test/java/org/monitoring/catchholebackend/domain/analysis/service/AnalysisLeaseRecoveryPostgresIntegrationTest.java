package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.Version;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobFailRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobProgressRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobLeaseRecoveryRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingComparisonBatch;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingComparisonBatchRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSubjectResolutionType;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

/** 실제 DB의 후보 조회와 후속 잠금 사이를 동기화하며 외부 Worker·LLM은 호출하지 않는다. */
@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Import(AnalysisLeaseRecoveryPostgresIntegrationTest.SqlHookConfiguration.class)
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("PostgreSQL 만료 lease 회수 동시성")
class AnalysisLeaseRecoveryPostgresIntegrationTest {

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.2-pg16"))
            .withEnv("POSTGRES_DB", "gh282_test")
            .withEnv("POSTGRES_USER", "gh282")
            .withEnv("POSTGRES_PASSWORD", "gh282-test-only")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(1)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/gh282_test");
        properties.add("spring.datasource.username", () -> "gh282");
        properties.add("spring.datasource.password", () -> "gh282-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '10s'");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private AnalysisJobWorkerService worker;
    @Autowired private AnalysisJobRepository jobs;
    @Autowired private AnalysisJobLeaseRecoveryRepository recovery;
    @Autowired private WorkRepository works;
    @Autowired private MemberRepository members;
    @Autowired private EpisodeRepository episodes;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecoverySqlInspector inspector;
    @Autowired private AiTokenService tokenService;
    @Autowired private WorldSettingCandidateRepository worldCandidates;
    @Autowired private WorldSettingComparisonBatchRepository worldBatches;
    @Autowired private SettingCandidateRepository characterCandidates;
    private TransactionTemplate tx;

    @BeforeEach
    void prepareDedicatedDatabase() {
        tx = new TransactionTemplate(transactions);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("gh282_test");
        jdbc.execute("TRUNCATE TABLE works, members CASCADE");
        System.out.printf("GH282 database=%s isolation=%s hibernate=%s%n",
                jdbc.queryForObject("show server_version", String.class),
                jdbc.queryForObject("show transaction_isolation", String.class), Version.getVersionString());
    }

    @ParameterizedTest(name = "만료된 {0}회차 뒤의 새 lease를 유지한다")
    @ValueSource(ints = {1, 2})
    @DisplayName("후행 회수는 이미 재claim된 2·3회차의 유효 lease를 변경하지 않는다")
    void preservesReclaimedLeaseAfterConcurrentExpiredCandidateRead(int expiredAttempt) throws Exception {
        UUID jobId = expiredJob(expiredAttempt);
        RecoveryGate gate = new RecoveryGate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            // B는 만료 후보를 읽고 아직 어떤 회수용 row lock도 얻지 않은 상태에서 대기한다.
            var delayedRecovery = executor.submit(() -> inspector.withGate(gate,
                    () -> worker.claimAnalysisJob(claimRequest())));
            assertThat(gate.beforeLock.await(15, TimeUnit.SECONDS))
                    .as("만료 후보 SELECT 이후 첫 locking SQL에 도달해야 한다: %s", gate.summary())
                    .isTrue();

            // A는 별도 트랜잭션에서 실제 회수·재claim·커밋을 완료한다.
            WorkerAnalysisJobPayload current = worker.claimAnalysisJob(claimRequest()).orElseThrow();
            assertThat(current.analysisJobId()).isEqualTo(jobId);
            assertThat(current.claimAttemptCount()).isEqualTo(expiredAttempt + 1);
            assertThat(current.leaseExpiresAt()).isAfter(LocalDateTime.now());
            gate.allowLock.countDown();
            Optional<WorkerAnalysisJobPayload> duplicate = delayedRecovery.get(20, TimeUnit.SECONDS);

            JobState actual = state(jobId, current.leaseToken());
            System.out.printf("GH282 race expiredAttempt=%d actualStatus=%s actualAttempt=%d sameLease=%s "
                            + "duplicateClaim=%s sql=%s%n", expiredAttempt, actual.status(), actual.claimAttemptCount(),
                    actual.sameLease(), duplicate.isPresent(), gate.summary());

            assertThat(actual.status()).isEqualTo(AnalysisJobStatus.RUNNING);
            assertThat(actual.claimAttemptCount()).isEqualTo(expiredAttempt + 1);
            // lease 원문을 assertion 실패 출력에 남기지 않는다.
            assertThat(actual.sameLease()).as("새 Worker의 lease가 그대로 유지되어야 한다").isTrue();
            assertThat(actual.checkpoint()).isEqualTo(AnalysisJobCheckpointStage.CHUNKS_READY);
            assertThat(duplicate.isEmpty()).as("후행 회수가 같은 Job을 다시 배정하지 않아야 한다").isTrue();
            assertThatCode(() -> worker.updateProgress(jobId, current.leaseToken(),
                    new WorkerAnalysisJobProgressRequest("회수 후 실행", EpisodeStatus.ANALYZING,
                            AnalysisJobCheckpointStage.CHUNKS_READY))).doesNotThrowAnyException();
        } finally {
            gate.allowLock.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest(name = "캐시에 남은 {0}회차 이후에도 최신 lease를 따른다")
    @ValueSource(ints = {1, 2})
    @DisplayName("Job과 Work가 1차 캐시에 있어도 다른 트랜잭션의 재claim을 덮어쓰지 않는다")
    void refreshesPreviouslyManagedJobAndWorkAfterConcurrentReclaim(int expiredAttempt) throws Exception {
        UUID jobId = expiredJob(expiredAttempt);
        var race = raceAfterCandidateRead(jobId, true,
                () -> worker.claimAnalysisJob(claimRequest()).orElseThrow());
        JobState after = state(jobId, race.changed().leaseToken());
        assertThat(after.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(after.claimAttemptCount()).isEqualTo(expiredAttempt + 1);
        assertThat(after.sameLease()).isTrue();
        assertThat(race.delayed().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("후보 조회 뒤 다른 트랜잭션이 만료 시각을 연장하면 회수하지 않는다")
    void preservesLeaseExtendedAfterCandidateRead() throws Exception {
        UUID jobId = expiredJob(3);
        UUID originalLease = leaseOf(jobId);
        LocalDateTime extendedUntil = LocalDateTime.now().plusMinutes(10).withNano(0);
        var race = raceAfterCandidateRead(jobId, true, () -> {
            // 이미 만료된 lease의 실제 heartbeat는 거절된다. 잠금 뒤 최신 만료 시각을 읽는지 검사한다.
            tx.executeWithoutResult(status -> jobs.findById(jobId).orElseThrow().renewLease(extendedUntil));
            return extendedUntil;
        });
        JobState after = state(jobId, originalLease);
        assertThat(after.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(after.claimAttemptCount()).isEqualTo(3);
        assertThat(after.sameLease()).isTrue();
        assertThat(tx.<LocalDateTime>execute(status -> jobs.findById(jobId).orElseThrow().getLeaseExpiresAt()))
                .isEqualTo(extendedUntil);
        assertThat(race.delayed().isEmpty()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisJobStatus.class, names = {"SUCCEEDED", "FAILED"})
    @DisplayName("후보 조회 후 완료·실패가 커밋되면 오래된 회수 요청은 terminal 상태를 유지한다")
    void preservesTerminalStateCommittedAfterCandidateRead(AnalysisJobStatus terminal) throws Exception {
        UUID jobId = expiredJob(1);
        var race = raceAfterCandidateRead(jobId, true, () -> {
            tx.executeWithoutResult(status -> {
                AnalysisJob job = jobs.findById(jobId).orElseThrow();
                if (terminal == AnalysisJobStatus.SUCCEEDED) job.succeed("{}", 0, 0);
                else job.fail(AnalysisFailureCode.UNEXPECTED_ERROR, "합성 실패");
            });
            return terminal;
        });
        assertThat(state(jobId, null).status()).isEqualTo(terminal);
        assertThat(state(jobId, null).claimAttemptCount()).isEqualTo(1);
        assertThat(race.delayed().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("1차 캐시의 Work가 ACTIVE여도 DB에서 삭제 중으로 바뀌면 회수하지 않는다")
    void refreshesPreviouslyManagedWorkLifecycleBeforeRecovery() throws Exception {
        UUID jobId = expiredJob(1);
        UUID originalLease = leaseOf(jobId);
        var race = raceAfterCandidateRead(jobId, true, () -> {
            tx.executeWithoutResult(status -> jobs.findById(jobId).orElseThrow().getWork().startPurging());
            return true;
        });
        JobState after = state(jobId, originalLease);
        assertThat(after.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(after.claimAttemptCount()).isEqualTo(1);
        assertThat(after.sameLease()).isTrue();
        assertThat(race.delayed().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("재claim 후 이전 lease의 progress·complete·fail은 거절하고 현재 실행은 완료한다")
    void rejectsOldLeaseReportsAndAllowsCurrentExecutionToComplete() {
        UUID jobId = expiredJob(1);
        UUID oldLease = leaseOf(jobId);
        WorkerAnalysisJobPayload current = worker.claimAnalysisJob(claimRequest()).orElseThrow();
        WorkerAnalysisJobProgressRequest progress = new WorkerAnalysisJobProgressRequest(
                "합성 완료 준비", EpisodeStatus.ANALYZING, AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
        assertLeaseConflict(() -> worker.updateProgress(jobId, oldLease, progress));
        assertLeaseConflict(() -> worker.completeAnalysisJob(jobId, oldLease,
                new WorkerAnalysisJobCompleteRequest("{}", null, null)));
        assertLeaseConflict(() -> worker.failAnalysisJob(jobId, oldLease,
                new WorkerAnalysisJobFailRequest(AnalysisFailureCode.UNEXPECTED_ERROR, "이전 실행의 합성 오류")));
        worker.updateProgress(jobId, current.leaseToken(), progress);
        worker.completeAnalysisJob(jobId, current.leaseToken(),
                new WorkerAnalysisJobCompleteRequest("{}", null, null));
        assertThat(state(jobId, null).status()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
        assertThatThrownBy(() -> worker.completeAnalysisJob(jobId, current.leaseToken(),
                new WorkerAnalysisJobCompleteRequest("{}", null, null)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_JOB_STATUS_CONFLICT));
        assertThat(state(jobId, null).status()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("실제로 만료된 세 번째 lease는 기존 한도 정책대로 실패한다")
    void failsGenuinelyExpiredThirdExecution() {
        UUID jobId = expiredJob(3);
        assertThat(worker.claimAnalysisJob(claimRequest()).isEmpty()).isTrue();
        assertThat(state(jobId, null).status()).isEqualTo(AnalysisJobStatus.FAILED);
        assertThat(state(jobId, null).claimAttemptCount()).isEqualTo(3);
        assertThat(tx.<AnalysisFailureCode>execute(status -> jobs.findById(jobId).orElseThrow().getFailureCode()))
                .isEqualTo(AnalysisFailureCode.WORKER_LEASE_EXPIRED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("일반 claim은 전용 관계 Worker의 만료된 세 번째 실행을 회수하지 않는다")
    void excludesDedicatedRelationshipJobsFromOrdinaryLeaseRecovery(boolean includeOrdinaryJobType) {
        UUID relationshipId = expiredJob(3, AnalysisJobType.RELATIONSHIP_EXTRACTION);
        UUID relationshipLease = leaseOf(relationshipId);
        UUID ordinaryId = expiredJob(1);
        Set<AnalysisJobType> allowedTypes = includeOrdinaryJobType
                ? Set.of(AnalysisJobType.RELATIONSHIP_EXTRACTION, AnalysisJobType.SETTING_EXTRACTION)
                : Set.of(AnalysisJobType.RELATIONSHIP_EXTRACTION);
        var result = worker.claimAnalysisJob(new WorkerAnalysisJobClaimRequest(null, "합성 claim", allowedTypes));

        if (includeOrdinaryJobType) {
            assertThat(result.orElseThrow().analysisJobId()).isEqualTo(ordinaryId);
            assertThat(state(ordinaryId, null).claimAttemptCount()).isEqualTo(2);
        } else {
            assertThat(result).isEmpty();
            assertThat(state(ordinaryId, null).claimAttemptCount()).isEqualTo(1);
        }
        JobState unchanged = state(relationshipId, relationshipLease);
        assertThat(unchanged.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(unchanged.claimAttemptCount()).isEqualTo(3);
        assertThat(unchanged.sameLease()).isTrue();
        assertThat(unchanged.checkpoint()).isEqualTo(AnalysisJobCheckpointStage.CHUNKS_READY);
        assertThat(jdbc.queryForObject(
                "select count(*) from analysis_failure_notifications where analysis_job_id = ?",
                Long.class, relationshipId)).isZero();
    }

    @Test
    @DisplayName("잠금 뒤 refresh는 같은 요청에서 수정한 작품 정보와 Job checkpoint를 보존한다")
    void preservesPendingWorkAndJobChangesBeforeLockedRefresh() {
        UUID jobId = expiredJob(1);
        String updatedTitle = "요청 안에서 변경한 합성 작품";
        UUID workId = tx.execute(status -> {
            AnalysisJob job = jobs.findById(jobId).orElseThrow();
            Work work = job.getWork();
            work.updateInfo(updatedTitle, WorkGenre.FANTASY, "동일 요청의 변경");
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
            List<AnalysisJob> recoverable = recovery.findExpiredLeasesForUpdate(
                    Set.of(AnalysisJobType.SETTING_EXTRACTION), LocalDateTime.now(), 20);
            assertThat(recoverable).extracting(AnalysisJob::getId).containsExactly(jobId);
            assertThat(recoverable.getFirst().getCheckpointStage())
                    .isEqualTo(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
            assertThat(work.getTitle()).isEqualTo(updatedTitle);
            return work.getId();
        });
        assertThat(state(jobId, null).checkpoint()).isEqualTo(AnalysisJobCheckpointStage.WORLD_CANDIDATES_PUBLISHED);
        assertThat(jdbc.queryForObject("select title from works where id = ?", String.class, workId))
                .isEqualTo(updatedTitle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lease_token", "lease_expires_at"})
    @DisplayName("null token 또는 만료 시각을 가진 기존 RUNNING 작업도 한 번 회수한다")
    void recoversIncompleteLegacyLease(String missingColumn) {
        UUID jobId = expiredJob(1);
        // 컬럼은 테스트의 고정 ValueSource 두 값으로만 선택한다.
        assertThat(Set.of("lease_token", "lease_expires_at")).contains(missingColumn);
        if (missingColumn.equals("lease_token")) {
            jdbc.update("update analysis_jobs set lease_expires_at = ?, lease_token = null where id = ?",
                    LocalDateTime.now().plusMinutes(5), jobId);
        } else {
            jdbc.update("update analysis_jobs set lease_expires_at = null where id = ?", jobId);
        }
        WorkerAnalysisJobPayload current = worker.claimAnalysisJob(claimRequest()).orElseThrow();
        assertThat(current.claimAttemptCount()).isEqualTo(2);
        assertThat(state(jobId, current.leaseToken()).sameLease()).isTrue();
        assertThat(current.leaseExpiresAt()).isAfter(LocalDateTime.now());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("후행 회수는 새 실행의 예약 토큰과 처리 중 비교 후보·묶음을 보존한다")
    void preservesNewExecutionReservationAndComparisonSideEffects(int expiredAttempt) throws Exception {
        UUID jobId = expiredJob(expiredAttempt);
        var race = raceAfterCandidateRead(jobId, true, () -> {
            WorkerAnalysisJobPayload current = worker.claimAnalysisJob(claimRequest()).orElseThrow();
            UUID requestId = UUID.randomUUID();
            tokenService.reserve(new AiTokenReserveRequest(requestId, jobId, AiTokenPurpose.SETTING_EXTRACTION,
                    1, "fake-test-only", 100), current.leaseToken());
            return processingWorldComparison(jobId, requestId, current.leaseToken());
        });
        ProcessingEffects effects = race.changed();
        assertThat(race.delayed().isEmpty()).isTrue();
        assertThat(state(jobId, effects.lease()).sameLease()).isTrue();
        assertThat(jdbc.queryForObject("select status from ai_token_usages where request_id = ?",
                String.class, effects.requestId())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id = ?",
                BigDecimal.class, effects.memberId())).isEqualByComparingTo(effects.reservedCharge());
        assertThat(jdbc.queryForObject("select comparison_status from world_setting_candidates where id = ?",
                String.class, effects.candidateId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select status from world_setting_comparison_batches where id = ?",
                String.class, effects.batchId())).isEqualTo("PROCESSING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"works", "analysis_jobs"})
    @DisplayName("다른 트랜잭션이 Work 또는 Job을 잠그면 건너뛰고 다른 작품을 claim한다")
    void skipsLockedRecoveryRowAndClaimsAnotherWork(String lockedTable) throws Exception {
        UUID lockedJobId = expiredJob(1);
        UUID otherJobId = expiredJob(1);
        tx.executeWithoutResult(status -> jobs.findById(otherJobId).orElseThrow().requeueExpiredLease());
        UUID rowId = lockedTable.equals("works")
                ? tx.execute(status -> jobs.findById(lockedJobId).orElseThrow().getWork().getId()) : lockedJobId;
        assertThat(Set.of("works", "analysis_jobs")).contains(lockedTable);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from " + lockedTable + " where id = ? for update", UUID.class, rowId);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("잠금 해제 제한 시간 초과");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("잠금 유지 테스트 중단", exception);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var claimant = executor.submit(() -> worker.claimAnalysisJob(claimRequest()));
            WorkerAnalysisJobPayload claimed = claimant.get(5, TimeUnit.SECONDS).orElseThrow();
            assertThat(claimed.analysisJobId()).isEqualTo(otherJobId);
            assertThat(state(lockedJobId, null).claimAttemptCount()).isEqualTo(1);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}의 만료된 {1}회차 뒤 새 실행과 연결 후보를 유지한다")
    @CsvSource({
            "CHARACTER_FACT_COMPARISON, 1",
            "CHARACTER_FACT_COMPARISON, 2",
            "WORLD_SETTING_COMPARISON, 1",
            "WORLD_SETTING_COMPARISON, 2"
    })
    @DisplayName("숨김 캐릭터·세계관 비교 Job도 동시 회수에서 새 lease와 처리 중 후보를 보존한다")
    void preservesHiddenComparisonJobAndCandidateAfterConcurrentReclaim(AnalysisJobType type, int expiredAttempt)
            throws Exception {
        HiddenComparison fixture = expiredHiddenComparison(type, expiredAttempt);
        WorkerAnalysisJobClaimRequest request = new WorkerAnalysisJobClaimRequest(null, "합성 비교 claim", Set.of(type));
        var race = raceAfterCandidateRead(fixture.jobId(), true, request, () -> {
            WorkerAnalysisJobPayload current = worker.claimAnalysisJob(request).orElseThrow();
            assertThat(current.analysisJobId()).isEqualTo(fixture.jobId());
            UUID linkedCandidate = type == AnalysisJobType.CHARACTER_FACT_COMPARISON
                    ? current.settingCandidateId() : current.worldSettingCandidateId();
            assertThat(linkedCandidate).isEqualTo(fixture.candidateId());
            tx.executeWithoutResult(status -> {
                if (type == AnalysisJobType.CHARACTER_FACT_COMPARISON) {
                    characterCandidates.findById(fixture.candidateId()).orElseThrow().startComparison();
                } else {
                    worldCandidates.findById(fixture.candidateId()).orElseThrow().startComparison();
                }
            });
            return current;
        });
        WorkerAnalysisJobPayload current = race.changed();
        JobState after = state(fixture.jobId(), current.leaseToken());
        assertThat(after.status()).isEqualTo(AnalysisJobStatus.RUNNING);
        assertThat(after.claimAttemptCount()).isEqualTo(expiredAttempt + 1);
        assertThat(after.sameLease()).isTrue();
        assertThat(race.delayed().isEmpty()).isTrue();
        String candidateTable = type == AnalysisJobType.CHARACTER_FACT_COMPARISON
                ? "setting_candidates" : "world_setting_candidates";
        assertThat(jdbc.queryForObject("select comparison_status from " + candidateTable + " where id = ?",
                String.class, fixture.candidateId())).isEqualTo("PROCESSING");
        worker.updateProgress(fixture.jobId(), current.leaseToken(),
                new WorkerAnalysisJobProgressRequest("숨김 비교 진행", null, null));
        assertThat(state(fixture.sourceJobId(), null).status()).isEqualTo(AnalysisJobStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select status from episodes where id = ?", String.class, fixture.episodeId()))
                .isEqualTo("ANALYZED");
    }

    private HiddenComparison expiredHiddenComparison(AnalysisJobType type, int expiredAttempt) {
        UUID sourceId = expiredJob(1);
        return tx.execute(status -> {
            AnalysisJob source = jobs.findById(sourceId).orElseThrow();
            source.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            source.succeed("{}", 0, 0);
            source.getEpisode().markAnalyzed();
            var json = JsonNodeFactory.instance;
            AnalysisJob hidden;
            UUID candidateId;
            if (type == AnalysisJobType.CHARACTER_FACT_COMPARISON) {
                SettingCandidate candidate = characterCandidates.save(SettingCandidate.create(
                        source.getWork(), source.getEpisode(), null, source, SettingEntityType.CHARACTER,
                        "합성 인물", "stats.strength", "10", SettingValueType.NUMBER,
                        json.objectNode().put("value", 10), json.arrayNode(), new BigDecimal("0.90"), json.objectNode()));
                candidate.startComparison();
                hidden = AnalysisJob.createCharacterFactComparison(candidate);
                candidateId = candidate.getId();
            } else {
                WorldSettingCandidate candidate = worldCandidates.save(WorldSettingCandidate.create(
                        source.getWork(), source.getEpisode(), source, WorldSettingCategory.RACE,
                        "합성 종족", "무기", "합성 검", json.arrayNode(), new BigDecimal("0.90"), json.objectNode()));
                candidate.startComparison();
                hidden = AnalysisJob.createWorldSettingComparison(candidate);
                candidateId = candidate.getId();
            }
            jobs.save(hidden);
            for (int index = 0; index < expiredAttempt; index++) {
                hidden.claim(null, "합성 비교 실행", LocalDateTime.now().minusMinutes(1));
            }
            return new HiddenComparison(hidden.getId(), candidateId, sourceId, source.getEpisode().getId());
        });
    }

    private <T> RaceResult<T> raceAfterCandidateRead(UUID jobId, boolean preload, Supplier<T> commitChange)
            throws Exception {
        return raceAfterCandidateRead(jobId, preload, claimRequest(), commitChange);
    }

    private <T> RaceResult<T> raceAfterCandidateRead(UUID jobId, boolean preload,
            WorkerAnalysisJobClaimRequest request, Supplier<T> commitChange) throws Exception {
        RecoveryGate gate = new RecoveryGate();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var delayed = executor.submit(() -> tx.execute(status -> {
                if (preload) {
                    AnalysisJob cached = jobs.findById(jobId).orElseThrow();
                    cached.getWork().getLifecycleStatus();
                }
                return inspector.withGate(gate, () -> worker.claimAnalysisJob(request));
            }));
            assertThat(gate.beforeLock.await(15, TimeUnit.SECONDS))
                    .as("회수 후보 조회 이후 첫 locking SQL에 도달해야 한다: %s", gate.summary()).isTrue();
            T changed = commitChange.get();
            gate.allowLock.countDown();
            Optional<WorkerAnalysisJobPayload> result = delayed.get(20, TimeUnit.SECONDS);
            System.out.printf("GH282 interleaving preload=%s sql=%s%n", preload, gate.summary());
            return new RaceResult<>(changed, result);
        } finally {
            gate.allowLock.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID leaseOf(UUID jobId) {
        return tx.execute(status -> jobs.findById(jobId).orElseThrow().getLeaseToken());
    }

    private void assertLeaseConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getResultCode()).isEqualTo(AnalysisJobErrorCode.ANALYSIS_JOB_LEASE_CONFLICT));
    }

    private ProcessingEffects processingWorldComparison(UUID jobId, UUID requestId, UUID lease) {
        return tx.execute(status -> {
            AnalysisJob job = jobs.findById(jobId).orElseThrow();
            var json = JsonNodeFactory.instance;
            WorldSettingCandidate candidate = worldCandidates.save(WorldSettingCandidate.create(
                    job.getWork(), job.getEpisode(), job, WorldSettingCategory.RACE, "합성 종족", "무기", "합성 검",
                    json.arrayNode().add(json.objectNode().put("quote", "합성 근거").put("startOffset", 0).put("endOffset", 5)),
                    new BigDecimal("0.9000"), json.objectNode()));
            candidate.resolveSubject(WorldSettingSubjectResolutionType.NEW, "NEW:합성 종족", "합성 종족", json.arrayNode());
            WorldSettingComparisonBatch batch = worldBatches.save(WorldSettingComparisonBatch.create(
                    job.getWork(), job.getEpisode(), job, candidate.getCategory(), candidate.getScopeName(),
                    candidate.getSubjectResolutionType(), candidate.getCanonicalSubjectKey(),
                    candidate.getCanonicalSubjectName(), candidate.getResolvedTargetWorldSettingIds(), 1));
            candidate.startComparison(batch, "C1");
            Long memberId = job.getWork().getMember().getId();
            BigDecimal reserved = jdbc.queryForObject("select reserved_tokens from ai_token_accounts where member_id = ?",
                    BigDecimal.class, memberId);
            return new ProcessingEffects(requestId, memberId, reserved, candidate.getId(), batch.getId(), lease);
        });
    }

    private record RaceResult<T>(T changed, Optional<WorkerAnalysisJobPayload> delayed) { }
    private record ProcessingEffects(UUID requestId, Long memberId, BigDecimal reservedCharge,
                                     UUID candidateId, UUID batchId, UUID lease) { }
    private record HiddenComparison(UUID jobId, UUID candidateId, UUID sourceJobId, UUID episodeId) { }

    private UUID expiredJob(int attempt) {
        return expiredJob(attempt, AnalysisJobType.SETTING_EXTRACTION);
    }

    private UUID expiredJob(int attempt, AnalysisJobType type) {
        return tx.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            String phone = "010" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000000));
            Member member = members.save(Member.register("lease-" + suffix + "@example.com", "test-password",
                    phone, "합성 작가"));
            Work work = works.save(Work.create(member, "lease 동시성 합성 작품", WorkGenre.FANTASY, "합성 자료"));
            Episode episode = episodes.save(Episode.create(work, null, 1, "합성 회차", "test/gh282/source",
                    "v1", "a".repeat(64), 100));
            AnalysisJob job = jobs.save(AnalysisJob.create(work, null, episode, type));
            for (int index = 0; index < attempt; index++) {
                job.claim(null, "합성 실행", LocalDateTime.now().minusMinutes(1));
            }
            job.updateCheckpointStage(AnalysisJobCheckpointStage.CHUNKS_READY);
            return job.getId();
        });
    }

    private WorkerAnalysisJobClaimRequest claimRequest() {
        return new WorkerAnalysisJobClaimRequest(null, "합성 claim", Set.of(AnalysisJobType.SETTING_EXTRACTION));
    }

    private JobState state(UUID jobId, UUID expectedLease) {
        return tx.execute(status -> {
            AnalysisJob job = jobs.findById(jobId).orElseThrow();
            return new JobState(job.getStatus(), job.getClaimAttemptCount(), job.hasLease(expectedLease),
                    job.getCheckpointStage());
        });
    }

    private record JobState(AnalysisJobStatus status, int claimAttemptCount, boolean sameLease,
                            AnalysisJobCheckpointStage checkpoint) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlHookConfiguration {
        @Bean
        RecoverySqlInspector recoverySqlInspector() {
            return new RecoverySqlInspector();
        }

        @Bean
        HibernatePropertiesCustomizer recoverySqlCustomizer(RecoverySqlInspector inspector) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", inspector);
        }
    }

    static final class RecoverySqlInspector implements StatementInspector {
        private final ThreadLocal<RecoveryGate> active = new ThreadLocal<>();

        <T> T withGate(RecoveryGate gate, java.util.function.Supplier<T> action) {
            active.set(gate);
            try {
                return action.get();
            } finally {
                active.remove();
            }
        }

        @Override
        public String inspect(String sql) {
            RecoveryGate gate = active.get();
            if (gate != null) gate.inspect(sql);
            return sql;
        }
    }

    static final class RecoveryGate {
        private final CountDownLatch beforeLock = new CountDownLatch(1);
        private final CountDownLatch allowLock = new CountDownLatch(1);
        private final List<String> statements = new ArrayList<>();
        private boolean candidateRead;
        private boolean paused;

        void inspect(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            synchronized (statements) {
                // SQL 형태만 수집하며 lease를 포함한 bind 값은 기록하지 않는다.
                statements.add(normalized);
            }
            if (normalized.contains("from analysis_jobs") && normalized.contains("lease_token is null")) {
                candidateRead = true;
                return;
            }
            if (candidateRead && !paused
                    && (normalized.contains("for update") || normalized.contains("for no key update"))) {
                paused = true;
                beforeLock.countDown();
                try {
                    if (!allowLock.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 회수 테스트의 잠금 재개 제한 시간을 초과했습니다.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("동시 회수 테스트가 중단되었습니다.", exception);
                }
            }
        }

        String summary() {
            synchronized (statements) {
                return statements.stream().map(sql -> {
                    int from = sql.indexOf(" from ");
                    return from < 0 ? sql.substring(0, Math.min(100, sql.length()))
                            : sql.substring(from, Math.min(from + 220, sql.length()));
                }).toList().toString();
            }
        }
    }
}
