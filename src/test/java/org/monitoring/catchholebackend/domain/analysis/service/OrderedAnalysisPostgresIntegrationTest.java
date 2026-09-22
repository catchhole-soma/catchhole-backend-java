package org.monitoring.catchholebackend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobCompleteRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateChange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.aitoken.dto.request.AiTokenReserveRequest;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.repository.CharacterFactRepository;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.member.repository.MemberRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 이 작업 전용 localhost gh180_test DB만 허용하며 운영/.env 연결을 읽지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "GH180_POSTGRES_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gh180_test")
class OrderedAnalysisPostgresIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GH180_POSTGRES_JDBC_URL"));
        properties.add("spring.datasource.username", () -> "gh180");
        properties.add("spring.datasource.password", () -> "gh180-test-only");
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private AnalysisRunStateService states;
    @Autowired private AnalysisJobWorkerService worker;
    @Autowired private AnalysisJobLeaseService leases;
    @Autowired private AnalysisJobRepository jobs;
    @Autowired private WorkRepository works;
    @Autowired private MemberRepository members;
    @Autowired private EpisodeRepository episodes;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiTokenService tokenService;
    @Autowired private CharacterFactRepository facts;
    @Autowired private SettingCandidateRepository candidates;
    @Autowired private WorkCharacterRepository characters;
    private TransactionTemplate tx;

    @BeforeEach
    void isolatedDatabaseOnly() {
        tx = new TransactionTemplate(transactions);
        // 명시적으로 허용한 새 테스트 DB에서만 이 클래스를 실행한다.
        jdbc.execute("TRUNCATE TABLE works, members CASCADE");
    }

    @Test
    @DisplayName("PostgreSQL 동시 claim은 같은 작품 하나만 허용하고 다른 작품은 진행한다")
    void concurrentClaimsSerializeOneWorkAndAllowAnother() throws Exception {
        Run first = run(10);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Optional<WorkerAnalysisJobPayload>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return worker.claimAnalysisJob(request());
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        }
        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(results.stream().flatMap(Optional::stream).findFirst().orElseThrow().analysisJobId())
                .isEqualTo(first.jobs().getFirst());
        Run other = run(2);
        assertThat(worker.claimAnalysisJob(request()).orElseThrow().workId()).isEqualTo(other.workId());
    }

    @Test
    @DisplayName("기록 저장 rollback은 다음 회차를 막고 seal과 완료를 함께 commit한 뒤에만 전달한다")
    void rollsBackPartialJournalThenSealsAtomically() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            AnalysisJob job = leases.getRunningAnalysisJobForUpdate(first.analysisJobId(), first.leaseToken());
            states.appendValidatedChanges(job, job.getInputStateHash(), List.of(reference()));
            throw new IllegalStateException("저장 중 실패 재현");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tx.<Integer>execute(status -> jobs.findById(first.analysisJobId()).orElseThrow()
                .getStateJournal().path("changes").size())).isZero();
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = leases.getRunningAnalysisJobForUpdate(first.analysisJobId(), first.leaseToken());
            states.appendValidatedChanges(job, job.getInputStateHash(), List.of(reference(), reference()));
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            worker.completeAnalysisJob(job.getId(), first.leaseToken(), new WorkerAnalysisJobCompleteRequest("{}", null, null));
        });
        WorkerAnalysisJobPayload second = worker.claimAnalysisJob(request()).orElseThrow();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().get(1));
        assertThat(tx.<String>execute(status -> states.getInputState(jobs.findById(second.analysisJobId()).orElseThrow())
                .at("/references/ep1/reason").asText())).isEqualTo("미해결 참고");
    }

    @Test
    @DisplayName("구 Worker를 제외하고 재claim 이후 늦은 완료와 원문 변경을 거절한다")
    void rejectsOldWorkerLeaseAndChangedSource() {
        Run run = run(2);
        assertThat(worker.claimAnalysisJob(new WorkerAnalysisJobClaimRequest(null, null,
                Set.of(AnalysisJobType.SETTING_EXTRACTION)))).isEmpty();
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        tx.executeWithoutResult(status -> jobs.findById(first.analysisJobId()).orElseThrow()
                .renewLease(LocalDateTime.now().minusSeconds(1)));
        WorkerAnalysisJobPayload reclaimed = worker.claimAnalysisJob(request()).orElseThrow();
        assertThat(reclaimed.analysisJobId()).isEqualTo(first.analysisJobId());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThatThrownBy(() -> worker.completeAnalysisJob(first.analysisJobId(), first.leaseToken(),
                new WorkerAnalysisJobCompleteRequest("{}", null, null))).isInstanceOf(AppException.class);
        tx.executeWithoutResult(status -> episodes.findById(run.episodes().getFirst()).orElseThrow()
                .updateContent(1, "수정", "changed-key", "v2", "b".repeat(64), 20));
        assertThatThrownBy(() -> worker.heartbeatAnalysisJob(reclaimed.analysisJobId(), reclaimed.leaseToken()))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("후속 실행을 무효화해도 당시 변경 기록을 보존하고 자동 재실행하지 않는다")
    void invalidatesTailWithoutReplayingPaidWork() {
        Run run = run(3);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = leases.getRunningAnalysisJobForUpdate(first.analysisJobId(), first.leaseToken());
            states.appendValidatedChanges(job, job.getInputStateHash(), List.of(reference()));
            states.invalidateFrom(job, "사용자가 앞 회차 후보를 수정했습니다.");
        });
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        assertThat(tx.<Boolean>execute(status -> jobs.findAllById(run.jobs()).stream()
                .allMatch(job -> job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED))).isTrue();
        assertThat(tx.<Integer>execute(status -> jobs.findById(first.analysisJobId()).orElseThrow()
                .getStateJournal().path("changes").size())).isEqualTo(1);
    }

    @Test
    @DisplayName("claim 시 원문 변경을 발견하면 무효화를 commit하고 다음 회차도 실행하지 않는다")
    void commitsInvalidationWhenClaimDetectsChangedSource() {
        Run run = run(3);
        tx.executeWithoutResult(status -> episodes.findById(run.episodes().getFirst()).orElseThrow()
                .updateContent(1, "수정", "changed-key", "v2", "b".repeat(64), 20));
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        assertThat(tx.<Boolean>execute(status -> jobs.findAllById(run.jobs()).stream()
                .allMatch(job -> job.getJournalStatus() == AnalysisJournalStatus.INVALIDATED
                        && job.getStatus() == AnalysisJobStatus.CANCELED))).isTrue();
    }

    @Test
    @DisplayName("원문이 같아도 회차 번호가 바뀌면 고정 실행 대상과 다른 결과로 거절한다")
    void rejectsChangedEpisodeOrderEvenWhenContentIsIdentical() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        tx.executeWithoutResult(status -> {
            Episode episode = episodes.findById(run.episodes().getFirst()).orElseThrow();
            episode.updateContent(9, episode.getTitle(), episode.getContentS3Key(), episode.getContentS3Version(),
                    episode.getContentHash(), episode.getCharCount());
        });
        assertThatThrownBy(() -> worker.heartbeatAnalysisJob(first.analysisJobId(), first.leaseToken()))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("사용자 변경은 영향 회차부터 무효화하며 앞 기록과 다른 작품·단일 모드를 보존한다")
    void invalidatesAffectedSuffixWithoutChangingEarlierOrUnrelatedJobs() {
        Run run = run(3);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        finishEmpty(first);
        WorkerAnalysisJobPayload second = worker.claimAnalysisJob(request()).orElseThrow();
        assertThat(second.analysisJobId()).isEqualTo(run.jobs().get(1));
        Run other = run(1);
        UUID single = tx.execute(status -> {
            Work work = works.findByIdForUpdate(run.workId()).orElseThrow();
            Episode episode = episodes.save(Episode.create(work, null, 11, "추가 회차", "test/extra", "v1",
                    "a".repeat(64), 10));
            return jobs.save(AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION)).getId();
        });
        tx.executeWithoutResult(status -> {
            works.findByIdForUpdate(run.workId()).orElseThrow();
            states.invalidateRunsForEpisodeChangeForUpdate(run.workId(), run.episodes().get(1), 2, "2화 원문이 변경되었습니다.");
        });
        tx.executeWithoutResult(status -> {
            assertThat(jobs.findById(first.analysisJobId()).orElseThrow().getJournalStatus())
                    .isEqualTo(AnalysisJournalStatus.SEALED);
            assertThat(jobs.findById(second.analysisJobId()).orElseThrow().getStatus())
                    .isEqualTo(AnalysisJobStatus.CANCELED);
            assertThat(jobs.findById(run.jobs().getLast()).orElseThrow().getJournalStatus())
                    .isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(jobs.findById(single).orElseThrow().getAnalysisMode()).isEqualTo(AnalysisMode.CONFIRMED_ONLY);
            assertThat(jobs.findById(single).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PENDING);
            assertThat(jobs.findById(other.jobs().getFirst()).orElseThrow().getJournalStatus())
                    .isEqualTo(AnalysisJournalStatus.PENDING);
            assertThat(jobs.findAllByAnalysisRunIdAndRunGenerationOrderByRunSequenceAsc(
                    jobs.findById(first.analysisJobId()).orElseThrow().getAnalysisRunId(), 1L)).hasSize(3);
        });
        assertThatThrownBy(() -> worker.heartbeatAnalysisJob(second.analysisJobId(), second.leaseToken()))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("다른 회차 출처가 들어간 변경 기록은 완료할 수 없어 후속 회차가 대기한다")
    void rejectsJournalWithForeignCandidateCoverage() {
        run(2);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = leases.getRunningAnalysisJobForUpdate(first.analysisJobId(), first.leaseToken());
            AnalysisStateChange foreign = new AnalysisStateChange("foreign:reference", List.of("references", "foreign"),
                    JsonNodeFactory.instance.objectNode().put("reason", "외부 출처"), false, "REVIEW_REQUIRED",
                    List.of(UUID.randomUUID()));
            states.appendValidatedChanges(job, job.getInputStateHash(), List.of(foreign));
        });
        assertThatThrownBy(() -> finishEmpty(first)).isInstanceOf(AppException.class);
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
        assertThat(tx.<AnalysisJobStatus>execute(status -> jobs.findById(first.analysisJobId()).orElseThrow().getStatus()))
                .isEqualTo(AnalysisJobStatus.RUNNING);
    }

    private void finishEmpty(WorkerAnalysisJobPayload payload) {
        tx.executeWithoutResult(status -> {
            AnalysisJob job = leases.getRunningAnalysisJobForUpdate(payload.analysisJobId(), payload.leaseToken());
            job.updateCheckpointStage(AnalysisJobCheckpointStage.WORLD_COMPARISONS_FINISHED);
            worker.completeAnalysisJob(job.getId(), payload.leaseToken(), new WorkerAnalysisJobCompleteRequest("{}", null, null));
        });
    }

    @Test
    @DisplayName("원문 파기는 과거 누적 기록의 복사된 근거도 정리하고 재개를 거절한다")
    void purgesEvidenceFromInvalidatedJournalAndRejectsResume() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        tx.executeWithoutResult(status -> {
            AnalysisJob job = leases.getRunningAnalysisJobForUpdate(first.analysisJobId(), first.leaseToken());
            states.appendValidatedChanges(job, job.getInputStateHash(), List.of(reference()));
        });
        finishEmpty(first);
        String oldOutputHash = tx.execute(status -> jobs.findById(first.analysisJobId()).orElseThrow()
                .getStateJournal().path("outputStateHash").asText());
        tx.executeWithoutResult(status -> {
            works.findByIdForUpdate(run.workId()).orElseThrow();
            states.purgeSourceEvidenceForWorkForUpdate(run.workId(), run.episodes().getFirst(), 1);
        });
        tx.executeWithoutResult(status -> {
            AnalysisJob job = jobs.findById(first.analysisJobId()).orElseThrow();
            assertThat(job.getJournalStatus()).isEqualTo(AnalysisJournalStatus.INVALIDATED);
            assertThat(job.getStateJournal().at("/changes/0/value/reason").isMissingNode()).isTrue();
            assertThat(job.getStateJournal().path("outputStateHash").asText()).isEqualTo(oldOutputHash);
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                states.validateResume(jobs.findById(first.analysisJobId()).orElseThrow())))
                .isInstanceOf(AppException.class);
        assertThat(worker.claimAnalysisJob(request())).isEmpty();
    }

    @Test
    @DisplayName("실행 무효화는 회차의 진행 상태와 남은 토큰 예약도 원자적으로 정리한다")
    void releasesReservationAndEpisodeStateWhenInvalidated() {
        Run run = run(2);
        WorkerAnalysisJobPayload first = worker.claimAnalysisJob(request()).orElseThrow();
        UUID requestId = UUID.randomUUID();
        tokenService.reserve(new AiTokenReserveRequest(requestId, first.analysisJobId(),
                AiTokenPurpose.SETTING_EXTRACTION, 1, "fake-test-only", 100), first.leaseToken());
        tx.executeWithoutResult(status -> {
            works.findByIdForUpdate(run.workId()).orElseThrow();
            episodes.findById(run.episodes().getFirst()).orElseThrow().updateStatus(EpisodeStatus.ANALYZING);
            states.invalidateRunsForEpisodeChangeForUpdate(run.workId(), run.episodes().getFirst(), 1, "앞 회차 원문이 바뀌었습니다.");
        });
        assertThat(jdbc.queryForObject("select status from ai_token_usages where request_id = ?", String.class, requestId))
                .isEqualTo("RELEASED");
        assertThat(tx.<EpisodeStatus>execute(status -> episodes.findById(run.episodes().getFirst()).orElseThrow().getStatus()))
                .isEqualTo(EpisodeStatus.FAILED);
        assertThatThrownBy(() -> tokenService.reserve(new AiTokenReserveRequest(UUID.randomUUID(), first.analysisJobId(),
                AiTokenPurpose.SETTING_EXTRACTION, 2, "fake-test-only", 100), first.leaseToken()))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("현재 slot이 비어도 미래 회차 Fact 이력이 있으면 과거 S0 생성을 거절한다")
    void rejectsFutureFactHistoryEvenWhenCurrentSlotIsAbsent() {
        Run run = run(10);
        tx.executeWithoutResult(status -> {
            Work work = works.findById(run.workId()).orElseThrow();
            Episode episode = episodes.findById(run.episodes().get(9)).orElseThrow();
            WorkCharacter character = characters.save(WorkCharacter.create(work, "주인공", null, null, null,
                    null, null, null, null, null, null));
            facts.save(CharacterFact.create(character, null, CharacterFactType.STATUS, "부상", "회복됨", null,
                    JsonNodeFactory.instance.objectNode().put("active", false), episode, null,
                    jobs.findById(run.jobs().get(9)).orElseThrow(), null, 10));
        });
        assertPastRunRejected(run, 5);
    }

    @Test
    @DisplayName("설정 slot이 없는 미래 회차 인물 발견도 과거 S0에 포함하지 않는다")
    void rejectsFutureDiscoveryWithoutAnyCurrentFact() {
        Run run = run(10);
        tx.executeWithoutResult(status -> {
            SettingCandidate discovery = candidates.save(SettingCandidate.createCharacterDiscovery(
                    works.findById(run.workId()).orElseThrow(), episodes.findById(run.episodes().get(9)).orElseThrow(),
                    null, jobs.findById(run.jobs().get(9)).orElseThrow(), "미래 인물", "미래 인물", null,
                    SettingCandidateMatchStatus.UNRESOLVED, null, null, null));
            discovery.confirm();
        });
        assertPastRunRejected(run, 5);
    }

    private void assertPastRunRejected(Run run, int episodeNo) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Work work = works.findByIdForUpdate(run.workId()).orElseThrow();
            Episode episode = episodes.findById(run.episodes().get(episodeNo - 1)).orElseThrow();
            states.initializeRun(List.of(AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION)));
        })).isInstanceOf(AppException.class);
    }

    private Run run(int count) {
        return tx.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Member member = members.save(Member.register(suffix + "@example.com", "password", "010" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000000)), "테스트 작가"));
            Work work = works.save(Work.create(member, "격리 작품 " + suffix, WorkGenre.FANTASY, "테스트"));
            List<AnalysisJob> created = new ArrayList<>();
            List<UUID> episodeIds = new ArrayList<>();
            for (int number = 1; number <= count; number++) {
                Episode episode = episodes.save(Episode.create(work, null, number, "회차", "test/" + suffix + "/" + number,
                        "v1", "a".repeat(64), 10));
                episodeIds.add(episode.getId());
                created.add(AnalysisJob.create(work, null, episode, AnalysisJobType.SETTING_EXTRACTION));
            }
            states.initializeRun(created);
            return new Run(work.getId(), created.stream().map(AnalysisJob::getId).toList(), episodeIds);
        });
    }

    private WorkerAnalysisJobClaimRequest request() {
        return new WorkerAnalysisJobClaimRequest(null, null, Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(AnalysisMode.CONFIRMED_ONLY, AnalysisMode.ORDERED_PROVISIONAL));
    }

    private AnalysisStateChange reference() {
        return new AnalysisStateChange("ep1:reference", List.of("references", "ep1"),
                JsonNodeFactory.instance.objectNode().put("reason", "미해결 참고"), false, "REVIEW_REQUIRED",
                List.of());
    }

    private record Run(UUID workId, List<UUID> jobs, List<UUID> episodes) { }
}
