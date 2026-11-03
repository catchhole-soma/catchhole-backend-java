package org.monitoring.catchholebackend.domain.relationship.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;

import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.monitoring.catchholebackend.domain.aitoken.entity.AiTokenUsage;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenPurpose;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageStatus;
import org.monitoring.catchholebackend.domain.analysis.dto.request.*;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobWorkerService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.entity.*;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillItemState;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillWorkState;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipBackfillManifest;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.entity.SettingBookRevision;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;
import org.monitoring.catchholebackend.global.storage.StoredTextObject;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "analysis.relationship-backfill.enabled=true",
            "analysis.relationship-backfill.scheduling-enabled=false"
        })
@ActiveProfiles("test")
class RelationshipBackfillIntegrationTest {
    @Autowired RelationshipBackfillService coordinator;
    @Autowired AnalysisJobWorkerService coreWorker;
    @Autowired AnalysisRunStateService coreRunState;
    @MockitoSpyBean RelationshipGraphService graph;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AnalysisJobRepository jobs;
    @MockitoBean RelationshipBackfillManifest manifest;
    @MockitoBean ObjectStorageService storage;
    @Autowired Sha256Hasher hasher;
    @MockitoBean AiTokenService tokens;
    UUID workId;
    Long owner;
    String source = "아라와 보라는 함께 길을 떠났다.";
    LocalDateTime selectedAt;
    final WorkerAnalysisJobClaimRequest request =
            new WorkerAnalysisJobClaimRequest(
                    "gpt-6.1-terra",
                    null,
                    Set.of(AnalysisJobType.RELATIONSHIP_EXTRACTION),
                    Set.of(AnalysisMode.CONFIRMED_ONLY),
                    false,
                    Set.of(AnalysisSourceKind.EPISODE),
                    false,
                    false,
                    false,
                    true);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(coordinator, "enabled", true);
        // This suite uses its own H2 or disposable PostgreSQL instance only.
        tx.executeWithoutResult(
                t -> {
                    em.createQuery("delete from RelationshipBackfillItem").executeUpdate();
                    em.createQuery("delete from RelationshipBackfillWork").executeUpdate();
                    em.createQuery("delete from RelationshipBackfillCampaign").executeUpdate();
                    em.createQuery(
                                    "select j from AnalysisJob j where j.status=:status",
                                    AnalysisJob.class)
                            .setParameter("status", AnalysisJobStatus.RUNNING)
                            .getResultList()
                            .forEach(AnalysisJob::cancelForWorkPurge);
                });
        when(storage.contentHash(anyString()))
                .thenAnswer(invocation -> hasher.hash(invocation.getArgument(0)));
        when(storage.getText(anyString())).thenReturn(source);
        when(tokens.getAnalysisJobTokenTotals(any())).thenReturn(new long[] {0, 0});
        selectedAt = LocalDateTime.now().plusSeconds(1);
        when(manifest.campaignKey()).thenReturn("relationship-backfill-20261030-v1");
        when(manifest.selectedAt()).thenReturn(selectedAt);
        tx.executeWithoutResult(
                t -> {
                    var member =
                            Member.register(
                                    UUID.randomUUID() + "@backfill.test",
                                    "password",
                                    null,
                                    "합성 작가");
                    em.persist(member);
                    owner = member.getId();
                    var work = Work.create(member, "합성 백필", WorkGenre.FANTASY, null);
                    em.persist(work);
                    workId = work.getId();
                });
        when(manifest.matches(any()))
                .thenAnswer(i -> ((Member) i.getArgument(0)).getId().equals(owner));
    }

    UUID episode(int no) {
        return tx.execute(
                t -> {
                    var e =
                            Episode.create(
                                    em.find(Work.class, workId),
                                    null,
                                    no,
                                    no + "화",
                                    "synthetic/" + UUID.randomUUID(),
                                    "v1",
                                    storage.contentHash(source),
                                    source.length());
                    em.persist(e);
                    return e.getId();
                });
    }

    WorkerAnalysisJobPayload claim() {
        return coordinator.claimRelationshipJob(request).orElseThrow();
    }

    WorkerAnalysisJobClaimRequest partialRequest() {
        return new WorkerAnalysisJobClaimRequest("gpt-6.1-terra", null,
                Set.of(AnalysisJobType.RELATIONSHIP_EXTRACTION), Set.of(AnalysisMode.CONFIRMED_ONLY), false,
                Set.of(AnalysisSourceKind.EPISODE), false, false, false, true, true, true);
    }

    Context prepareTwoPairs(WorkerAnalysisJobPayload payload) {
        return graph.prepareRelationshipAnalysis(payload.analysisJobId(), payload.leaseToken(),
                new Prepare(1, storage.contentHash(source), List.of(
                        new Observation("O1", new Endpoint("아라", "아라"), new Endpoint("보라", "보라"),
                                "함께 이동한다", List.of(new Evidence(0, source.length(), source))),
                        new Observation("O2", new Endpoint("아라", "아라"), new Endpoint("다라", "다라"),
                                "함께 이동한다", List.of(new Evidence(0, source.length(), source)))), 0));
    }

    Decision pairDecision(Context context, String ref) {
        var observation = context.observations().stream().filter(o -> o.ref().equals(ref)).findFirst().orElseThrow();
        String sourceRef = observation.sourceNodeRefs().getFirst(), targetRef = observation.targetNodeRefs().getFirst();
        var existing = context.relationships().stream().filter(r -> Set.of(r.sourceNodeRef(), r.targetNodeRef())
                .equals(Set.of(sourceRef, targetRef))).findFirst();
        return existing.map(r -> new Decision(List.of(ref), Operation.MERGE, r.ref(), r.sourceNodeRef(), r.targetNodeRef(),
                        r.axis(), r.kind(), r.label(), r.summary(), r.direction(), TemporalScope.PRESENT, null))
                .orElseGet(() -> new Decision(List.of(ref), Operation.ADD, null, sourceRef, targetRef, Axis.INTERACTION,
                        Kind.COOPERATION, "협력", "함께 이동한다", Direction.MUTUAL, TemporalScope.PRESENT, null));
    }

    @Test
    @DisplayName("한 인물 쌍 실패를 보존하고 독립 쌍을 진행·공개하되 같은 쌍의 후행 변경을 차단한다")
    void partialPairPublishesIndependentProgressAndPreservesPrefix() {
        source = "아라와 보라와 다라는 함께 길을 떠났다.";
        when(storage.getText(anyString())).thenReturn(source);
        episode(1); episode(2); episode(3);
        coordinator.bootstrapRelationshipBackfill();
        for (int no = 1; no <= 3; no++) {
            var payload = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
            assertThat(payload.relationshipAnalysisContract().supportsPartialResults()).isTrue();
            var context = prepareTwoPairs(payload);
            var decisions = no == 1 ? List.of(pairDecision(context, "O1"), pairDecision(context, "O2"))
                    : List.of(pairDecision(context, "O2"));
            var failures = no == 1 ? List.<GroupFailure>of() : List.of(new GroupFailure(List.of("O1"),
                    no == 2 ? "RELATIONSHIP_ENDPOINTS_UNGROUNDED" : "RELATIONSHIP_PREDECESSOR_INCOMPLETE"));
            assertThat(context.blockedPairs()).hasSize(no == 3 ? 1 : 0);
            var complete = new Complete(1, context.contextToken(), decisions, failures);
            graph.completeRelationshipAnalysis(payload.analysisJobId(), payload.leaseToken(), complete);
            // Restart between graph completion and Job completion must preserve the failure receipt.
            assertThat(graph.getRelationshipAnalysis(payload.analysisJobId(), payload.leaseToken()).orElseThrow().failures())
                    .isEqualTo(failures);
            graph.completeRelationshipAnalysis(payload.analysisJobId(), payload.leaseToken(), complete);
            coordinator.completeRelationshipJob(payload.analysisJobId(), payload.leaseToken(), null);
            coordinator.completeRelationshipJob(payload.analysisJobId(), payload.leaseToken(), null);
            if (no == 2) assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        }
        assertThat(items()).extracting(RelationshipBackfillItem::getState).containsExactly(
                RelationshipBackfillItemState.SUCCEEDED, RelationshipBackfillItemState.PARTIAL, RelationshipBackfillItemState.PARTIAL);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        var result = graph.getRelationshipGraph(owner, workId, 3);
        assertThat(result.updateIncomplete()).isTrue();
        assertThat(result.incompleteEpisodeNos()).containsExactly(2, 3);
        assertThat(result.analysisStatus()).isEqualTo("PARTIAL");
        assertThat(result.edges()).hasSize(2);
        assertThat(result.edges().stream().filter(Edge::updateIncomplete)).singleElement()
                .extracting(Edge::lastVerifiedEpisodeNo).isEqualTo(1);
        assertThat(result.edges().stream().filter(e -> !e.updateIncomplete())).singleElement()
                .extracting(Edge::lastVerifiedEpisodeNo).isEqualTo(3);
        var past = graph.getRelationshipGraph(owner, workId, 1);
        assertThat(past.updateIncomplete()).isFalse();
        assertThat(past.incompleteEpisodeNos()).isEmpty();
        assertThat(past.edges()).allSatisfy(e -> assertThat(e.lastVerifiedEpisodeNo()).isEqualTo(1));
    }

    @Test
    @DisplayName("구 동결 문맥은 새 Worker 재선점에서도 계약을 승격하지 않고 새 부분 계약은 구 Worker에 배정하지 않는다")
    void frozenCapabilitySurvivesReclaimInBothDirections() {
        episode(1); episode(2);
        coordinator.bootstrapRelationshipBackfill();
        var legacy = claim();
        var saved = prepare(legacy);
        tx.executeWithoutResult(t -> em.find(AnalysisJob.class, legacy.analysisJobId())
                .renewLease(LocalDateTime.now().minusMinutes(1)));
        var modern = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        assertThat(modern.analysisJobId()).isEqualTo(legacy.analysisJobId());
        assertThat(modern.relationshipAnalysisContract().supportsPartialResults()).isFalse();
        assertThat(graph.getRelationshipAnalysis(modern.analysisJobId(), modern.leaseToken()).orElseThrow()).isEqualTo(saved);
        finish(modern, saved);
        var next = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        prepare(next);
        tx.executeWithoutResult(t -> em.find(AnalysisJob.class, next.analysisJobId())
                .renewLease(LocalDateTime.now().minusMinutes(1)));
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        var resumed = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        assertThat(resumed.analysisJobId()).isEqualTo(next.analysisJobId());
        assertThat(resumed.relationshipAnalysisContract().supportsPartialResults()).isTrue();
    }

    private static java.util.stream.Stream<Arguments> legacyCoreBackfillCases() {
        return java.util.stream.Stream.of(RelationshipBackfillWorkState.BUILDING, RelationshipBackfillWorkState.BLOCKED)
                .flatMap(state -> java.util.stream.Stream.of(Arguments.of(state, false), Arguments.of(state, null)));
    }

    @ParameterizedTest
    @MethodSource("legacyCoreBackfillCases")
    @DisplayName("활성 백필의 관계를 이관하는 일반 분석은 구 Worker도 선점하고 관계 전용 작업은 보호한다")
    void legacyCoreClaimDefersRelationshipsInActiveBackfill(RelationshipBackfillWorkState workState, Boolean partialSupport) {
        seedPartialClaimPrefix(true);
        if (workState == RelationshipBackfillWorkState.BLOCKED)
            tx.executeWithoutResult(t -> em.find(RelationshipBackfillWork.class, workId).block("ANALYSIS_FAILED"));
        // Disabling new backfill dispatch does not undo an existing work's inline deferral.
        ReflectionTestUtils.setField(coordinator, "enabled", false);
        UUID core = pendingCoreJob(3, false);
        var payload = coreWorker.claimAnalysisJob(coreClaimRequest(partialSupport)).orElseThrow();
        assertThat(payload.analysisJobId()).isEqualTo(core);
        assertThat(payload.relationshipAnalysisContract()).isNull();
        tx.executeWithoutResult(t -> {
            var job = em.find(AnalysisJob.class, core);
            assertThat(job.isRelationshipAnalysisEnabled()).isFalse();
            assertThat(job.isRelationshipPartialResultsEnabled()).isFalse();
        });
        ReflectionTestUtils.setField(coordinator, "enabled", true);
        assertThat(coordinator.claimRelationshipJob(relationshipClaimRequest(partialSupport))).isEmpty();
        if (workState == RelationshipBackfillWorkState.BUILDING) {
            var relationship = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
            assertThat(relationship.episode().episodeNo()).isEqualTo(2);
            assertThat(relationship.relationshipAnalysisContract().supportsPartialResults()).isTrue();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    @DisplayName("부분 관계 실패가 있어도 관계 분석을 하지 않는 설정집은 구 Worker가 선점한다")
    void legacySettingBookClaimIgnoresRelationshipPartialPrefix(Boolean partialSupport) {
        seedPartialClaimPrefix(false);
        UUID bookJob = tx.execute(t -> {
            var work = em.find(Work.class, workId);
            var batch = UploadBatch.create(work, work.getMember(), UploadType.SETTING_BOOK, UploadSourceType.FILE);
            em.persist(batch);
            var book = UploadFile.create(batch, UploadFileRole.SETTING_BOOK, "합성 설정.txt", "text/plain", "s3://synthetic/book", 10);
            book.markParsed();
            em.persist(book);
            var revision = SettingBookRevision.create(book, 1,
                    new StoredTextObject("synthetic/book-revision", "v1", storage.contentHash(source), source.length()));
            em.persist(revision);
            book.linkCurrentRevision(revision);
            var job = AnalysisJob.createSettingBook(work, book, revision);
            coreRunState.initializeRun(List.of(job));
            return job.getId();
        });
        var payload = coreWorker.claimAnalysisJob(coreClaimRequest(partialSupport)).orElseThrow();
        assertThat(payload.analysisJobId()).isEqualTo(bookJob);
        assertThat(payload.sourceKind()).isEqualTo(AnalysisSourceKind.SETTING_BOOK);
        assertThat(payload.relationshipAnalysisContract()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    @DisplayName("백필 완료 뒤 재개하는 inline 관계 분석은 부분 완료 지원 Worker만 선점한다")
    void completedBackfillStillRequiresPartialSupportForInlineClaim(Boolean partialSupport) {
        seedPartialClaimPrefix(false);
        UUID core = pendingCoreJob(2, false);
        assertThat(coreWorker.claimAnalysisJob(coreClaimRequest(partialSupport))).isEmpty();
        var payload = coreWorker.claimAnalysisJob(coreClaimRequest(true)).orElseThrow();
        assertThat(payload.analysisJobId()).isEqualTo(core);
        assertThat(payload.relationshipAnalysisContract().supportsPartialResults()).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    @DisplayName("활성 백필 예외도 이미 고정된 Job의 부분 완료 계약을 해제하지 않는다")
    void activeBackfillDoesNotBypassFrozenJobPartialCapability(Boolean partialSupport) {
        seedPartialClaimPrefix(true);
        UUID core = pendingCoreJob(3, true);
        assertThat(coreWorker.claimAnalysisJob(coreClaimRequest(partialSupport))).isEmpty();
        var payload = coreWorker.claimAnalysisJob(coreClaimRequest(true)).orElseThrow();
        assertThat(payload.analysisJobId()).isEqualTo(core);
        assertThat(payload.relationshipAnalysisContract()).isNull();
        tx.executeWithoutResult(t -> assertThat(em.find(AnalysisJob.class, core).isRelationshipPartialResultsEnabled()).isTrue());
    }

    private WorkerAnalysisJobClaimRequest coreClaimRequest(Boolean partialSupport) {
        return new WorkerAnalysisJobClaimRequest("synthetic", null, Set.of(AnalysisJobType.SETTING_EXTRACTION),
                Set.of(AnalysisMode.CONFIRMED_ONLY, AnalysisMode.ORDERED_PROVISIONAL), true,
                Set.of(AnalysisSourceKind.EPISODE, AnalysisSourceKind.SETTING_BOOK), true, false, true,
                true, true, partialSupport);
    }

    private WorkerAnalysisJobClaimRequest relationshipClaimRequest(Boolean partialSupport) {
        return new WorkerAnalysisJobClaimRequest("synthetic", null, Set.of(AnalysisJobType.RELATIONSHIP_EXTRACTION),
                Set.of(AnalysisMode.CONFIRMED_ONLY), false, Set.of(AnalysisSourceKind.EPISODE), false, false,
                false, true, true, partialSupport);
    }

    private UUID pendingCoreJob(int episodeNo, boolean frozenPartialContract) {
        UUID episodeId = episode(episodeNo);
        return tx.execute(t -> {
            var job = AnalysisJob.create(em.find(Work.class, workId), null, em.find(Episode.class, episodeId), AnalysisJobType.SETTING_EXTRACTION);
            if (frozenPartialContract) job.enableRelationshipPartialResults();
            em.persist(job);
            return job.getId();
        });
    }

    private void seedPartialClaimPrefix(boolean active) {
        tx.executeWithoutResult(t -> em.createQuery("select j from AnalysisJob j where j.status=:pending", AnalysisJob.class)
                .setParameter("pending", AnalysisJobStatus.PENDING).getResultList().forEach(AnalysisJob::cancelForWorkPurge));
        episode(1);
        if (active) episode(2);
        coordinator.bootstrapRelationshipBackfill();
        var relationship = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        var context = prepare(relationship);
        graph.completeRelationshipAnalysis(relationship.analysisJobId(), relationship.leaseToken(),
                new Complete(1, context.contextToken(), List.of(),
                        List.of(new GroupFailure(List.of("O1"), "RELATIONSHIP_ENDPOINTS_UNGROUNDED"))));
        coordinator.completeRelationshipJob(relationship.analysisJobId(), relationship.leaseToken(), null);
        assertThat(state().active()).isEqualTo(active);
    }

    Context prepare(WorkerAnalysisJobPayload p) {
        return graph.prepareRelationshipAnalysis(
                p.analysisJobId(),
                p.leaseToken(),
                new Prepare(
                        1,
                        storage.contentHash(source),
                        List.of(
                                new Observation(
                                        "O1",
                                        new Endpoint("아라", "아라"),
                                        new Endpoint("보라", "보라"),
                                        "함께 길을 떠난다",
                                        List.of(new Evidence(0, source.length(), source)))),
                        0));
    }

    void finish(WorkerAnalysisJobPayload p) {
        var saved = graph.getRelationshipAnalysis(p.analysisJobId(), p.leaseToken());
        Context c = saved.orElseGet(() -> prepare(p));
        finish(p, c);
    }

    void finish(WorkerAnalysisJobPayload p, Context c) {
        var o = c.observations().getFirst();
        Decision d =
                c.relationships().isEmpty()
                        ? new Decision(
                                List.of("O1"),
                                Operation.ADD,
                                null,
                                o.sourceNodeRefs().getFirst(),
                                o.targetNodeRefs().getFirst(),
                                Axis.SOCIAL,
                                Kind.COMPANIONSHIP,
                                "동행",
                                "함께 길을 떠난다",
                                Direction.MUTUAL,
                                TemporalScope.PRESENT,
                                null)
                        : new Decision(
                                List.of("O1"),
                                Operation.MERGE,
                                c.relationships().getFirst().ref(),
                                c.relationships().getFirst().sourceNodeRef(),
                                c.relationships().getFirst().targetNodeRef(),
                                Axis.SOCIAL,
                                Kind.COMPANIONSHIP,
                                c.relationships().getFirst().label(),
                                c.relationships().getFirst().summary(),
                                Direction.MUTUAL,
                                TemporalScope.PRESENT,
                                null);
        graph.completeRelationshipAnalysis(
                p.analysisJobId(), p.leaseToken(), new Complete(1, c.contextToken(), List.of(d)));
        coordinator.completeRelationshipJob(
                p.analysisJobId(),
                p.leaseToken(),
                new WorkerAnalysisJobCompleteRequest(null, null, null));
    }

    void coreComplete(UUID episodeId) {
        tx.executeWithoutResult(
                t -> {
                    var e = em.find(Episode.class, episodeId);
                    var j =
                            AnalysisJob.create(
                                    e.getWork(), null, e, AnalysisJobType.SETTING_EXTRACTION);
                    em.persist(j);
                    assertThat(coordinator.deferInlineRelationshipAnalysis(j)).isTrue();
                    coordinator.enqueueCompletedCoreAnalysis(j);
                    j.succeed(null, 0, 0);
                });
    }

    UUID priorRelationshipUsage(UUID episodeId, AiTokenUsageStatus status) {
        return tx.execute(
                t -> {
                    var episode = em.find(Episode.class, episodeId);
                    var job =
                            AnalysisJob.create(
                                    episode.getWork(),
                                    null,
                                    episode,
                                    status == AiTokenUsageStatus.UNKNOWN
                                            ? AnalysisJobType.RELATIONSHIP_EXTRACTION
                                            : AnalysisJobType.SETTING_EXTRACTION);
                    em.persist(job);
                    var usage =
                            AiTokenUsage.reserve(
                                    UUID.randomUUID(),
                                    job,
                                    AiTokenPurpose.RELATIONSHIP_EXTRACTION,
                                    1,
                                    "gpt-6.1-terra",
                                    100);
                    if (status == AiTokenUsageStatus.SETTLED)
                        usage.settle(40, 0, 20, AiTokenUsageOutcome.SUCCESS);
                    else if (status != AiTokenUsageStatus.RESERVED)
                        usage.release(AiTokenUsageOutcome.WORKER_LEASE_EXPIRED);
                    assertThat(usage.getStatus()).isEqualTo(status);
                    em.persist(usage);
                    job.succeed(null, 0, 0);
                    return job.getId();
                });
    }

    boolean servicePaidJob(UUID jobId) {
        return tx.execute(t -> em.find(AnalysisJob.class, jobId).isRelationshipServicePaid());
    }

    RelationshipBackfillWork state() {
        return tx.execute(t -> em.find(RelationshipBackfillWork.class, workId));
    }

    List<RelationshipBackfillItem> items() {
        return tx.execute(
                t ->
                        em.createQuery(
                                        "select i from RelationshipBackfillItem i where"
                                                + " i.workId=:work order by i.episodeNo",
                                        RelationshipBackfillItem.class)
                                .setParameter("work", workId)
                                .getResultList());
    }

    private void confirmedName(UUID episodeId, String sourceName, String currentName) {
        tx.executeWithoutResult(
                t -> {
                    var episode = em.find(Episode.class, episodeId);
                    var work = episode.getWork();
                    var character =
                            org.monitoring.catchholebackend.domain.character.entity.WorkCharacter
                                    .create(
                                            work,
                                            currentName,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null);
                    em.persist(character);
                    var job =
                            AnalysisJob.create(
                                    work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
                    em.persist(job);
                    job.succeed(null, 0, 0);
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var candidate =
                            org.monitoring.catchholebackend.domain.character.entity.SettingCandidate
                                    .create(
                                            work,
                                            episode,
                                            UUID.randomUUID(),
                                            job,
                                            org.monitoring.catchholebackend.domain.character.type
                                                    .SettingEntityType.CHARACTER,
                                            sourceName,
                                            sourceName,
                                            character.getId(),
                                            org.monitoring.catchholebackend.domain.character.type
                                                    .SettingCandidateMatchStatus.MATCHED,
                                            "profile.설명",
                                            "여행자",
                                            org.monitoring.catchholebackend.domain.character.type
                                                    .SettingValueType.STRING,
                                            json.createObjectNode().put("value", "여행자"),
                                            json.createArrayNode(),
                                            java.math.BigDecimal.ONE,
                                            json.createObjectNode());
                    candidate.confirm();
                    em.persist(candidate);
                });
    }

    @Test
    @DisplayName("확정 후보의 과거 이름만 연결하고 현재 이름이나 후행 프로필을 과거에 노출하지 않는다")
    void historicalIdentityIsCutoffSafe() {
        UUID one = episode(1), two = episode(2);
        confirmedName(one, "아라", "미래에 바뀐 호칭");
        confirmedName(two, "보라", "미래의 정체");
        coordinator.bootstrapRelationshipBackfill();
        var first = claim();
        var c1 = prepare(first);
        assertThat(c1.nodes())
                .anyMatch(n -> n.name().equals("아라") && n.profileRegistered())
                .anyMatch(n -> n.name().equals("보라") && !n.profileRegistered());
        assertThat(c1.nodes()).noneMatch(n -> n.name().startsWith("미래"));
        finish(first, c1);
        var second = claim();
        finish(second);
        assertThat(graph.getRelationshipGraph(owner, workId, 1).nodes())
                .anyMatch(n -> n.name().equals("보라") && !n.profileRegistered());
        assertThat(graph.getRelationshipGraph(owner, workId, 2).nodes())
                .anyMatch(n -> n.name().equals("보라") && n.profileRegistered());
    }

    @Test
    @DisplayName("고정 회원의 선택 시점 원고만 한 번 접수하고 관계 작업은 회차 상태를 바꾸지 않는다")
    void fixedScopeAndNoCoreMutation() {
        UUID one = episode(1), two = episode(2);
        UUID otherWork =
                tx.execute(
                        t -> {
                            var m =
                                    Member.register(
                                            UUID.randomUUID() + "@other.test",
                                            "password",
                                            null,
                                            "제외");
                            em.persist(m);
                            var w = Work.create(m, "제외작품", WorkGenre.FANTASY, null);
                            em.persist(w);
                            em.persist(
                                    Episode.create(
                                            w,
                                            null,
                                            1,
                                            null,
                                            "other",
                                            "v1",
                                            storage.contentHash(source),
                                            source.length()));
                            return w.getId();
                        });
        coordinator.bootstrapRelationshipBackfill();
        coordinator.bootstrapRelationshipBackfill();
        assertThat(items()).hasSize(2).allMatch(RelationshipBackfillItem::isServicePaid);
        assertThat(
                        tx.<RelationshipBackfillWork>execute(
                                t -> em.find(RelationshipBackfillWork.class, otherWork)))
                .isNull();
        var p = claim();
        assertThat(p.episode().episodeNo()).isEqualTo(1);
        assertThat(p.characterSettingSchemas()).isEmpty();
        assertThat(p.knownCharacters()).isEmpty();
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        finish(p);
        assertThat(tx.<EpisodeStatus>execute(t -> em.find(Episode.class, one).getStatus()))
                .isEqualTo(EpisodeStatus.UPLOADED);
        assertThat(tx.<EpisodeStatus>execute(t -> em.find(Episode.class, two).getStatus()))
                .isEqualTo(EpisodeStatus.UPLOADED);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from setting_candidates where work_id=?",
                                Long.class,
                                workId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_setting_candidates where work_id=?",
                                Long.class,
                                workId))
                .isZero();
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges()).isEmpty();
        finish(claim());
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        assertThat(graph.getRelationshipGraph(owner, workId, null).selectedEpisode().episodeNo())
                .isEqualTo(2);
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges()).hasSize(1);
    }

    @Test
    @DisplayName("진행 중 새 회차는 유료 후행으로 합류하고 공개 교체 이후 일반 inline으로 돌아간다")
    void newEpisodeJoinsAndInlineReturns() {
        episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        finish(claim());
        UUID three = episode(3);
        coreComplete(three);
        assertThat(items().getLast().isServicePaid()).isFalse();
        finish(claim());
        assertThat(state().active()).isTrue();
        var last = claim();
        assertThat(last.episode().episodeNo()).isEqualTo(3);
        assertThat(servicePaidJob(last.analysisJobId())).isFalse();
        finish(last);
        assertThat(state().getPublishedGeneration()).isEqualTo(1);
        assertThat(graph.getRelationshipGraph(owner, workId, 1).edges()).hasSize(1);
        tx.executeWithoutResult(
                t -> {
                    var e = em.find(Episode.class, three);
                    var j =
                            AnalysisJob.create(
                                    e.getWork(), null, e, AnalysisJobType.SETTING_EXTRACTION);
                    assertThat(coordinator.deferInlineRelationshipAnalysis(j)).isFalse();
                });
    }

    @Test
    @DisplayName("선정 시점 뒤 이미 완료된 일반 분석을 bootstrap이 수집하면 운영 부담으로 재구축한다")
    void bootstrapCompletedTailIsServicePaid() {
        episode(1);
        var two = episode(2);
        jdbc.update("update episodes set created_at=? where id=?", selectedAt.plusSeconds(1), two);
        priorRelationshipUsage(two, AiTokenUsageStatus.SETTLED);
        coordinator.bootstrapRelationshipBackfill();
        assertThat(items()).hasSize(2).allMatch(RelationshipBackfillItem::isServicePaid);
        finish(claim());
        var tail = claim();
        assertThat(tail.episode().episodeNo()).isEqualTo(2);
        assertThat(servicePaidJob(tail.analysisJobId())).isTrue();
    }

    @Test
    @DisplayName("백필 초기화는 대형 설정 JSON이 있는 성공 Job을 로드하지 않고 선정 경계·후행 회차를 한 번 접수한다")
    void bootstrapDoesNotHydrateCoreJobSnapshots() {
        selectedAt = LocalDateTime.now().plusMinutes(1).withNano(0);
        when(manifest.selectedAt()).thenReturn(selectedAt);
        UUID older = episode(1), boundary = episode(2), later = episode(3);
        jdbc.update("update episodes set created_at=? where id=?", selectedAt.minusSeconds(1), older);
        jdbc.update("update episodes set created_at=? where id=?", selectedAt, boundary);
        jdbc.update("update episodes set created_at=? where id=?", selectedAt.plusSeconds(1), later);
        var snapshot = JsonNodeFactory.instance.objectNode().put("syntheticPayload", "x".repeat(128 * 1024));
        snapshot.putArray("changes");
        snapshot.put("outputStateHash", "a".repeat(64));
        for (var episodeId : List.of(older, older, boundary, later, later)) {
            successfulCoreJob(
                    episodeId,
                    job -> {
                        job.configureReviewMode(AnalysisReviewMode.AUTOMATIC);
                        job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null, snapshot);
                        job.captureAutomaticInput(snapshot);
                        job.prepareOrderedInput("b".repeat(64));
                        job.replacePendingJournal(snapshot);
                        job.sealJournal();
                    });
        }

        tx.executeWithoutResult(t -> {
            coordinator.bootstrapRelationshipBackfill();
            // 비동기 결과 지표의 별도 Job 조회와 분리해 bootstrap의 영속성 문맥만 검사한다.
            assertThat(em.unwrap(Session.class).getStatistics().getEntityKeys())
                    .extracting(key -> ((EntityKey) key).getEntityName())
                    .as("원문 manifest만 필요할 때 설정 JSONB를 가진 Job 엔티티를 읽지 않는다")
                    .doesNotContain(AnalysisJob.class.getName());
        });
        assertThat(items()).extracting(RelationshipBackfillItem::getEpisodeId)
                .containsExactly(older, boundary, later);
    }

    @Test
    @DisplayName("후행 수집은 현재 원문 key·hash·nullable version이 같은 성공 분석만 회차당 한 번 접수한다")
    void bootstrapTailRequiresMatchingSuccessfulSource() {
        selectedAt = LocalDateTime.now().plusMinutes(1).withNano(0);
        when(manifest.selectedAt()).thenReturn(selectedAt);
        UUID older = episode(1), boundary = episode(2), later = episode(3), nullVersion = episode(4);
        UUID wrongKey = episode(5), wrongHash = episode(6), wrongVersion = episode(7);
        UUID nullJobVersion = episode(8), nullEpisodeVersion = episode(9), unfinished = episode(10);
        UUID otherJobType = episode(11);
        for (var episodeId : List.of(later, nullVersion, wrongKey, wrongHash, wrongVersion,
                nullJobVersion, nullEpisodeVersion, unfinished, otherJobType)) {
            jdbc.update("update episodes set created_at=? where id=?", selectedAt.plusSeconds(1), episodeId);
        }
        jdbc.update("update episodes set created_at=? where id=?", selectedAt, boundary);
        jdbc.update("update episodes set content_s3_version=null where id in (?, ?)", nullVersion, nullEpisodeVersion);
        successfulCoreJob(boundary, job -> {});
        successfulCoreJob(later, job -> {});
        successfulCoreJob(later, job -> {});
        successfulCoreJob(nullVersion, job -> {});
        successfulCoreJob(wrongKey, job -> ReflectionTestUtils.setField(job, "sourceContentS3Key", "synthetic/stale"));
        successfulCoreJob(wrongHash, job -> ReflectionTestUtils.setField(job, "sourceContentHash", "0".repeat(64)));
        successfulCoreJob(wrongVersion, job -> ReflectionTestUtils.setField(job, "sourceContentS3Version", "v2"));
        successfulCoreJob(nullJobVersion, job -> ReflectionTestUtils.setField(job, "sourceContentS3Version", null));
        successfulCoreJob(nullEpisodeVersion, job -> ReflectionTestUtils.setField(job, "sourceContentS3Version", "v1"));
        var unfinishedJob = successfulCoreJob(unfinished, job -> {});
        jdbc.update("update analysis_jobs set status='FAILED' where id=?", unfinishedJob);
        successfulCoreJob(otherJobType, job -> ReflectionTestUtils.setField(job, "jobType", AnalysisJobType.CHARACTER_FACT_COMPARISON));

        coordinator.bootstrapRelationshipBackfill();
        coordinator.bootstrapRelationshipBackfill();

        assertThat(items()).extracting(RelationshipBackfillItem::getEpisodeId)
                .containsExactly(older, boundary, later, nullVersion);
        assertThat(items()).allMatch(RelationshipBackfillItem::isServicePaid);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BUILDING);
    }

    private UUID successfulCoreJob(UUID episodeId, Consumer<AnalysisJob> customize) {
        return tx.execute(t -> {
            var episode = em.find(Episode.class, episodeId);
            var job = AnalysisJob.create(episode.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
            customize.accept(job);
            job.succeed(null, 0, 0);
            em.persist(job);
            return job.getId();
        });
    }

    @ParameterizedTest
    @EnumSource(AiTokenUsageStatus.class)
    @DisplayName("동일 원문의 이전 관계 원장이 있으면 상태와 무관하게 WAITING 항목을 서비스 부담으로 승격한다")
    void existingRelationshipUsagePromotesWaitingItem(AiTokenUsageStatus status) {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var two = episode(2);
        coreComplete(two);
        assertThat(items().getLast().isServicePaid()).isFalse();
        var originalJob = priorRelationshipUsage(two, status);
        coreComplete(two);
        assertThat(items().getLast().isServicePaid()).isTrue();
        assertThat(servicePaidJob(originalJob)).isFalse();
        finish(claim());
        var tail = claim();
        assertThat(servicePaidJob(tail.analysisJobId())).isTrue();
        assertThat(
                        tx.<AiTokenUsageStatus>execute(
                                t ->
                                        em.createQuery(
                                                        "select u.status from AiTokenUsage u where"
                                                            + " u.analysisJob.id=:job",
                                                        AiTokenUsageStatus.class)
                                                .setParameter("job", originalJob)
                                                .getSingleResult()))
                .isEqualTo(status);
    }

    @Test
    @DisplayName("다른 원문이나 일반 설정 목적의 원장은 새 관계 분석을 무료로 바꾸지 않는다")
    void differentSourceOrPurposeDoesNotPromote() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        for (int no = 2; no <= 5; no++) {
            var id = episode(no);
            priorRelationshipUsage(id, AiTokenUsageStatus.SETTLED);
            final int mismatch = no;
            tx.executeWithoutResult(
                    t -> {
                        var episode = em.find(Episode.class, id);
                        episode.updateContentStorage(
                                mismatch == 2 ? "another/key" : episode.getContentS3Key(),
                                mismatch == 3 ? "v2" : episode.getContentS3Version(),
                                mismatch == 4
                                        ? storage.contentHash("다른 원문")
                                        : episode.getContentHash());
                        if (mismatch == 5) {
                            // The frozen source number is immutable; change only this isolated test
                            // receipt.
                            jdbc.update(
                                    "update analysis_jobs set source_episode_no=99 where"
                                        + " episode_id=?",
                                    id);
                        }
                    });
            coreComplete(id);
            assertThat(items().getLast().isServicePaid()).isFalse();
        }
        var six = episode(6);
        tx.executeWithoutResult(
                t -> {
                    var e = em.find(Episode.class, six);
                    var j =
                            AnalysisJob.create(
                                    e.getWork(), null, e, AnalysisJobType.SETTING_EXTRACTION);
                    em.persist(j);
                    em.persist(
                            AiTokenUsage.reserve(
                                    UUID.randomUUID(),
                                    j,
                                    AiTokenPurpose.SETTING_EXTRACTION,
                                    1,
                                    "gpt-6.1-terra",
                                    100));
                    j.succeed(null, 0, 0);
                });
        coreComplete(six);
        assertThat(items().getLast().isServicePaid()).isFalse();
    }

    @Test
    @DisplayName("중간 회차가 늦게 추가되면 뒤 회차의 관측은 보존하고 비교만 다시 순서대로 수행한다")
    void lateMiddleRecomparesWithoutReextracting() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        coreComplete(episode(3));
        finish(claim());
        var oldThree = claim();
        assertThat(servicePaidJob(oldThree.analysisJobId())).isFalse();
        var c3 = prepare(oldThree);
        UUID two = episode(2);
        coreComplete(two);
        finish(oldThree, c3);
        assertThat(state().active()).isTrue();
        var second = claim();
        assertThat(second.episode().episodeNo()).isEqualTo(2);
        finish(second);
        var replay = claim();
        assertThat(replay.episode().episodeNo()).isEqualTo(3);
        assertThat(replay.analysisJobId()).isNotEqualTo(oldThree.analysisJobId());
        assertThat(servicePaidJob(replay.analysisJobId())).isTrue();
        assertThat(servicePaidJob(oldThree.analysisJobId())).isFalse();
        var saved =
                graph.getRelationshipAnalysis(replay.analysisJobId(), replay.leaseToken())
                        .orElseThrow();
        assertThat(saved.status()).isEqualTo(AnalysisStatus.PREPARED);
        assertThat(saved.relationships().getFirst().lastChangedEpisodeNo()).isEqualTo(2);
        finish(replay, saved);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        verify(storage, times(3)).getText(anyString());
    }

    @Test
    @DisplayName("이미 성공한 사용자 부담 후행 회차도 중간 삽입으로 재비교할 때는 서비스 부담이다")
    void completedUserPaidTailRebuildIsServicePaid() {
        episode(1);
        episode(4);
        coordinator.bootstrapRelationshipBackfill();
        coreComplete(episode(3));
        finish(claim());
        var oldThree = claim();
        assertThat(servicePaidJob(oldThree.analysisJobId())).isFalse();
        finish(oldThree);
        coreComplete(episode(2));
        assertThat(items().get(2).isServicePaid()).isTrue();
        finish(claim());
        var replay = claim();
        assertThat(replay.episode().episodeNo()).isEqualTo(3);
        assertThat(servicePaidJob(replay.analysisJobId())).isTrue();
        assertThat(servicePaidJob(oldThree.analysisJobId())).isFalse();
        assertThat(
                        graph.getRelationshipAnalysis(replay.analysisJobId(), replay.leaseToken())
                                .orElseThrow()
                                .status())
                .isEqualTo(AnalysisStatus.PREPARED);
    }

    @Test
    @DisplayName("lease 회수 뒤 서비스 부담으로 승격하면 원 유료 작업을 보존하고 새 작업으로 관측을 재사용한다")
    void promotedPendingJobIsReplacedWithoutChangingOriginalBilling() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var two = episode(2);
        coreComplete(two);
        finish(claim());
        var original = claim();
        prepare(original);
        assertThat(servicePaidJob(original.analysisJobId())).isFalse();
        priorRelationshipUsage(two, AiTokenUsageStatus.RESERVED);
        coreComplete(two);
        assertThat(items().getLast().isServicePaid()).isTrue();
        tx.executeWithoutResult(
                t ->
                        em.find(AnalysisJob.class, original.analysisJobId())
                                .renewLease(LocalDateTime.now().minusSeconds(1)));
        var replacement = claim();
        assertThat(replacement.analysisJobId()).isNotEqualTo(original.analysisJobId());
        assertThat(servicePaidJob(replacement.analysisJobId())).isTrue();
        assertThat(servicePaidJob(original.analysisJobId())).isFalse();
        assertThat(
                        tx.<AnalysisJobStatus>execute(
                                t ->
                                        em.find(AnalysisJob.class, original.analysisJobId())
                                                .getStatus()))
                .isEqualTo(AnalysisJobStatus.CANCELED);
        assertThat(
                        graph.getRelationshipAnalysis(
                                        replacement.analysisJobId(), replacement.leaseToken())
                                .orElseThrow()
                                .status())
                .isEqualTo(AnalysisStatus.PREPARED);
        finish(replacement);
        verify(storage, times(2)).getText(anyString());
    }

    @Test
    @DisplayName("중간 회차 삽입과 lease 회수가 겹쳐도 후행의 오래된 비교 문맥을 재사용하지 않는다")
    void expiredLaterContextIsRebuiltAfterMiddleInsertion() {
        episode(1);
        episode(3);
        coordinator.bootstrapRelationshipBackfill();
        finish(claim());
        var original = claim();
        prepare(original);
        tx.executeWithoutResult(
                t ->
                        em.find(AnalysisJob.class, original.analysisJobId())
                                .renewLease(LocalDateTime.now().minusSeconds(1)));
        coreComplete(episode(2));
        var second = claim();
        assertThat(second.episode().episodeNo()).isEqualTo(2);
        finish(second);
        var replay = claim();
        assertThat(replay.episode().episodeNo()).isEqualTo(3);
        assertThat(replay.analysisJobId()).isNotEqualTo(original.analysisJobId());
        assertThat(servicePaidJob(replay.analysisJobId())).isTrue();
        var context =
                graph.getRelationshipAnalysis(replay.analysisJobId(), replay.leaseToken())
                        .orElseThrow();
        assertThat(context.relationships().getFirst().lastChangedEpisodeNo()).isEqualTo(2);
        finish(replay, context);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        verify(storage, times(3)).getText(anyString());
    }

    @Test
    @DisplayName("백필 중에도 기존 공개 그래프와 사용자 정정은 유지하고 마지막에 같은 관계 ID로 교체한다")
    void publishedGraphAndCorrectionPreserved() {
        UUID one = episode(1);
        episode(2);
        var old =
                tx.execute(
                        t -> {
                            var e = em.find(Episode.class, one);
                            var j =
                                    AnalysisJob.create(
                                            e.getWork(),
                                            null,
                                            e,
                                            AnalysisJobType.SETTING_EXTRACTION);
                            j.enableRelationshipAnalysis();
                            em.persist(j);
                            em.flush();
                            j.claim("test", "test", LocalDateTime.now().plusMinutes(1));
                            graph.beginRelationshipAnalysis(j);
                            return new AbstractMap.SimpleEntry<>(j.getId(), j.getLeaseToken());
                        });
        Context c =
                graph.prepareRelationshipAnalysis(
                        old.getKey(),
                        old.getValue(),
                        new Prepare(
                                1,
                                storage.contentHash(source),
                                List.of(
                                        new Observation(
                                                "O1",
                                                new Endpoint("아라", "아라"),
                                                new Endpoint("보라", "보라"),
                                                "함께 길을 떠난다",
                                                List.of(new Evidence(0, source.length(), source)))),
                                0));
        var o = c.observations().getFirst();
        graph.completeRelationshipAnalysis(
                old.getKey(),
                old.getValue(),
                new Complete(
                        1,
                        c.contextToken(),
                        List.of(
                                new Decision(
                                        List.of("O1"),
                                        Operation.ADD,
                                        null,
                                        o.sourceNodeRefs().getFirst(),
                                        o.targetNodeRefs().getFirst(),
                                        Axis.SOCIAL,
                                        Kind.COMPANIONSHIP,
                                        "동행",
                                        "함께 길을 떠난다",
                                        Direction.MUTUAL,
                                        TemporalScope.PRESENT,
                                        null))));
        tx.executeWithoutResult(
                t -> {
                    var j = em.find(AnalysisJob.class, old.getKey());
                    graph.finalizeRelationshipAnalysis(j);
                    j.succeed(null, 0, 0);
                });
        var publicEdge = graph.getRelationshipGraph(owner, workId, null).edges().getFirst();
        graph.correctRelationshipEdge(
                owner, workId, publicEdge.id(), new Correction(publicEdge.version(), "작가 정정"));
        coordinator.bootstrapRelationshipBackfill();
        finish(claim());
        assertThat(graph.getRelationshipGraph(owner, workId, null).selectedEpisode().episodeNo())
                .isEqualTo(1);
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges().getFirst().summary())
                .isEqualTo("작가 정정");
        finish(claim());
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges().getFirst().id())
                .isEqualTo(publicEdge.id());
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges().getFirst().summary())
                .isEqualTo("작가 정정");
    }

    @Test
    @DisplayName("원문 교체를 발견하면 후행 관계를 공개하지 않고 현재 버전을 보존한다")
    void sourceReplacementBlocks() {
        UUID one = episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        tx.executeWithoutResult(
                t ->
                        em.find(Episode.class, one)
                                .updateContentStorage(
                                        "replaced", "v2", storage.contentHash("바뀐 원문")));
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(items().getFirst().getReason()).isEqualTo("SOURCE_CHANGED");
        assertThat(state().getPublishedGeneration()).isZero();
    }

    @Test
    @DisplayName("비교 저장 후 원문이 바뀌면 실제 반영 전에 거절하고 회차 실패와 분리한다")
    void sourceChangeBetweenDecisionAndFinalize() {
        UUID one = episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var p = claim();
        var c = prepare(p);
        var o = c.observations().getFirst();
        graph.completeRelationshipAnalysis(
                p.analysisJobId(),
                p.leaseToken(),
                new Complete(
                        1,
                        c.contextToken(),
                        List.of(
                                new Decision(
                                        List.of("O1"),
                                        Operation.ADD,
                                        null,
                                        o.sourceNodeRefs().getFirst(),
                                        o.targetNodeRefs().getFirst(),
                                        Axis.SOCIAL,
                                        Kind.COMPANIONSHIP,
                                        "동행",
                                        "함께 길을 떠난다",
                                        Direction.MUTUAL,
                                        TemporalScope.PRESENT,
                                        null))));
        tx.executeWithoutResult(
                t ->
                        em.find(Episode.class, one)
                                .updateContentStorage(
                                        "replaced", "v2", storage.contentHash("바뀐 원문")));
        assertThatThrownBy(() -> coordinator.completeRelationshipJob(p.analysisJobId(), p.leaseToken(), null))
                .isInstanceOf(AppException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from relationship_graph_events where work_id=?",
                                Long.class,
                                workId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from relationship_graph_edges where work_id=?",
                                Long.class,
                                workId))
                .isZero();
        assertThat(items().getFirst().getState()).isEqualTo(RelationshipBackfillItemState.RUNNING);
        coordinator.failRelationshipJob(
                p.analysisJobId(),
                p.leaseToken(),
                new WorkerAnalysisJobFailRequest(AnalysisFailureCode.UNEXPECTED_ERROR, "원문 변경"));
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(tx.<EpisodeStatus>execute(t -> em.find(Episode.class, one).getStatus()))
                .isEqualTo(EpisodeStatus.UPLOADED);
    }

    @Test
    @DisplayName("시작 전 삭제는 생략하되 저장 문맥이 있는 회차 삭제는 세대 공개를 차단한다")
    void archiveAfterPreparedBlocksPublication() {
        UUID one = episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        var p = claim();
        prepare(p);
        tx.executeWithoutResult(
                t -> {
                    em.find(AnalysisJob.class, p.analysisJobId())
                            .renewLease(LocalDateTime.now().minusSeconds(1));
                    em.find(Episode.class, one).archive();
                });
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(items().getFirst().getReason()).isEqualTo("SOURCE_ARCHIVED_AFTER_START");
        assertThat(state().getPublishedGeneration()).isZero();
    }

    @Test
    @DisplayName("아직 분석하지 않은 삭제 원고는 모델 호출 없이 생략하고 남은 회차만 공개한다")
    void archiveBeforeClaimSkipsSafely() {
        UUID one = episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        tx.executeWithoutResult(t -> em.find(Episode.class, one).archive());
        var p = claim();
        assertThat(p.episode().episodeNo()).isEqualTo(2);
        finish(p);
        assertThat(items().getFirst().getState()).isEqualTo(RelationshipBackfillItemState.SKIPPED);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges()).hasSize(1);
        verify(storage, times(1)).getText(anyString());
    }

    @Test
    @DisplayName("lease가 만료되면 같은 고정 문맥으로 재개하며 구 lease와 중복 완료를 구분한다")
    void leaseResumeAndIdempotentCompletion() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var first = claim();
        Context c = prepare(first);
        tx.executeWithoutResult(
                t ->
                        em.find(AnalysisJob.class, first.analysisJobId())
                                .renewLease(LocalDateTime.now().minusSeconds(1)));
        var second = claim();
        assertThat(second.analysisJobId()).isEqualTo(first.analysisJobId());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(
                        graph.getRelationshipAnalysis(second.analysisJobId(), second.leaseToken())
                                .orElseThrow()
                                .contextToken())
                .isEqualTo(c.contextToken());
        assertThatThrownBy(() -> coordinator.renewRelationshipJobLease(first.analysisJobId(), first.leaseToken()))
                .isInstanceOf(AppException.class);
        finish(second, c);
        coordinator.completeRelationshipJob(second.analysisJobId(), second.leaseToken(), null);
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges()).hasSize(1);
        verify(storage, times(1)).getText(anyString());
    }

    @Test
    @DisplayName("실패한 관계 작업은 회차와 캐릭터 분석을 실패로 바꾸지 않고 후행 공개만 보류한다")
    void failedRelationBlocksOnlyRelation() {
        UUID one = episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        var p = claim();
        coordinator.failRelationshipJob(
                p.analysisJobId(),
                p.leaseToken(),
                new WorkerAnalysisJobFailRequest(AnalysisFailureCode.UNEXPECTED_ERROR, "합성 실패"));
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(tx.<EpisodeStatus>execute(t -> em.find(Episode.class, one).getStatus()))
                .isEqualTo(EpisodeStatus.UPLOADED);
        coreComplete(episode(3));
        assertThat(items()).hasSize(3);
        assertThat(state().getPublishedGeneration()).isZero();
    }

    WorkerAnalysisJobClaimRequest recoveryRequest(Boolean supported) {
        return new WorkerAnalysisJobClaimRequest(
                request.modelName(), request.currentStep(), request.allowedJobTypes(),
                request.supportedAnalysisModes(), request.supportsCharacterComparisonGroups(),
                request.supportedSourceKinds(), request.supportsSettingBookReferenceTime(),
                request.supportsSemanticCurrentContext(), request.supportsFrozenComparisonEvidence(),
                request.supportsRelationshipAnalysis(), supported);
    }

    void failPreparedComparison(WorkerAnalysisJobPayload payload, FailureCode failure) {
        Context context = graph.getRelationshipAnalysis(payload.analysisJobId(), payload.leaseToken())
                .orElseGet(() -> prepare(payload));
        graph.failRelationshipAnalysis(payload.analysisJobId(), payload.leaseToken(),
                new Fail(1, context.sourceHash(), context.contextToken(), failure));
        coordinator.failRelationshipJob(payload.analysisJobId(), payload.leaseToken(),
                new WorkerAnalysisJobFailRequest(
                        AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "합성 관계 비교 실패"));
    }

    long relationshipJobCount() {
        return jdbc.queryForObject(
                "select count(*) from analysis_jobs where work_id=? and job_type='RELATIONSHIP_EXTRACTION'",
                Long.class, workId);
    }

    @ParameterizedTest
    @EnumSource(value = FailureCode.class, names = {"COMPARISON_FAILED", "INPUT_TOO_LARGE"})
    @DisplayName("실패한 관계 비교는 검증한 관측만 재사용하고 기존 정산을 보존한 채 후행까지 공개한다")
    void recoverPreparedComparisonWithoutReextractingOrRebilling(FailureCode failure) {
        episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, failure);
        UUID usageId = tx.execute(t -> {
            var usage = AiTokenUsage.reserve(UUID.randomUUID(),
                    em.find(AnalysisJob.class, original.analysisJobId()),
                    AiTokenPurpose.RELATIONSHIP_COMPARISON, 1, "gpt-6.1-terra", 100);
            usage.settle(40, 0, 20, AiTokenUsageOutcome.SUCCESS);
            em.persist(usage);
            return usage.getRequestId();
        });
        LocalDateTime originalCompletedAt = tx.execute(t ->
                em.find(AnalysisJob.class, original.analysisJobId()).getCompletedAt());
        clearInvocations(storage, tokens);

        var recovered = coordinator.claimRelationshipJob(recoveryRequest(true)).orElseThrow();
        assertThat(recovered.analysisJobId()).isNotEqualTo(original.analysisJobId());
        assertThat(servicePaidJob(recovered.analysisJobId())).isTrue();
        assertThat(items().getFirst().getRecoveryAttemptCount()).isEqualTo(1);
        var cached = graph.getRelationshipAnalysis(recovered.analysisJobId(), recovered.leaseToken())
                .orElseThrow();
        assertThat(cached.status()).isEqualTo(AnalysisStatus.PREPARED);
        assertThat(cached.observations()).hasSize(1);
        finish(recovered, cached);
        verify(storage, never()).getText(anyString());
        finish(claim());

        assertThat(items()).allMatch(i -> i.getState() == RelationshipBackfillItemState.SUCCEEDED);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        assertThat(state().getPublishedGeneration()).isEqualTo(state().getBuildingGeneration());
        assertThat(graph.getRelationshipGraph(owner, workId, null).edges()).hasSize(1);
        tx.executeWithoutResult(t -> {
            var old = em.find(AnalysisJob.class, original.analysisJobId());
            assertThat(old.getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
            assertThat(old.getFailureCode()).isEqualTo(AnalysisFailureCode.COMPARISON_VALIDATION_FAILED);
            assertThat(old.getCompletedAt()).isEqualTo(originalCompletedAt);
            assertThat(old.isRelationshipServicePaid()).isTrue();
            var usage = em.find(AiTokenUsage.class, usageId);
            assertThat(usage.getAnalysisJob().getId()).isEqualTo(original.analysisJobId());
            assertThat(usage.getStatus()).isEqualTo(AiTokenUsageStatus.SETTLED);
            assertThat(usage.getOutcome()).isEqualTo(AiTokenUsageOutcome.SUCCESS);
            assertThat(usage.getInputTokens()).isEqualTo(40L);
            assertThat(usage.getOutputTokens()).isEqualTo(20L);
            assertThat(usage.getReservedCharge()).isZero();
            assertThat(usage.getChargedTokens()).isZero();
        });
        verify(tokens, never()).releaseReservedForAnalysisJob(eq(original.analysisJobId()), any());
        verify(tokens, never()).ensureAnalysisCanStart(anyLong());
    }

    @Test
    @DisplayName("구 Worker는 실패 복구를 시작하지 못하고 한 번 복구한 실패는 새 Job으로 반복하지 않는다")
    void recoveryRequiresCapabilityAndPersistsSingleAttempt() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, FailureCode.COMPARISON_FAILED);
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(coordinator.claimRelationshipJob(recoveryRequest(false))).isEmpty();
        assertThat(items().getFirst().getRecoveryAttemptCount()).isZero();
        assertThat(relationshipJobCount()).isEqualTo(1);

        var recovered = coordinator.claimRelationshipJob(recoveryRequest(true)).orElseThrow();
        failPreparedComparison(recovered, FailureCode.COMPARISON_FAILED);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(coordinator.claimRelationshipJob(recoveryRequest(true))).isEmpty();
            assertThat(items().getFirst().getRecoveryAttemptCount()).isEqualTo(1);
            assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        }
        assertThat(relationshipJobCount()).isEqualTo(2);
        assertThat(items().getFirst().getJobId()).isEqualTo(recovered.analysisJobId());
    }

    @Test
    @DisplayName("복구 Job의 lease 재선점도 새 Worker만 가능하며 복구 예산을 다시 사용하지 않는다")
    void recoveryLeaseCannotBeReclaimedByLegacyWorker() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, FailureCode.COMPARISON_FAILED);
        var recovered = coordinator.claimRelationshipJob(recoveryRequest(true)).orElseThrow();
        tx.executeWithoutResult(t -> em.find(AnalysisJob.class, recovered.analysisJobId())
                .renewLease(LocalDateTime.now().minusSeconds(1)));
        clearInvocations(storage);

        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(coordinator.claimRelationshipJob(recoveryRequest(false))).isEmpty();
        assertThat(items().getFirst().getState()).isEqualTo(RelationshipBackfillItemState.WAITING);
        var resumed = coordinator.claimRelationshipJob(recoveryRequest(true)).orElseThrow();
        assertThat(resumed.analysisJobId()).isEqualTo(recovered.analysisJobId());
        assertThat(resumed.leaseToken()).isNotEqualTo(recovered.leaseToken());
        assertThat(items().getFirst().getRecoveryAttemptCount()).isEqualTo(1);
        assertThat(relationshipJobCount()).isEqualTo(2);
        finish(resumed);
        verify(storage, never()).getText(anyString());
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
    }

    @Test
    @DisplayName("관측 재준비에 실패하면 복구 예산과 Job 생성 및 이전 그래프 무효화를 모두 되돌린다")
    void failedMandatoryCachePreparationRollsBackRecovery() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, FailureCode.COMPARISON_FAILED);
        doThrow(new IllegalStateException("합성 재준비 실패"))
                .when(graph).prepareCachedObservations(any(), any());

        assertThatThrownBy(() -> coordinator.claimRelationshipJob(recoveryRequest(true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(items().getFirst().getRecoveryAttemptCount()).isZero();
        assertThat(items().getFirst().getState()).isEqualTo(RelationshipBackfillItemState.FAILED);
        assertThat(items().getFirst().getJobId()).isEqualTo(original.analysisJobId());
        assertThat(relationshipJobCount()).isEqualTo(1);
        assertThat(tx.<AnalysisStatus>execute(t ->
                em.find(RelationshipGraphAnalysis.class, original.analysisJobId()).getStatus()))
                .isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    @DisplayName("느린 관측 재준비 뒤 일반 claim은 같은 토큰과 시도 횟수로 새 60초 lease를 반환한다")
    void normalClaimRenewsLeaseAfterCachedPreparation() {
        episode(1);
        episode(3);
        coordinator.bootstrapRelationshipBackfill();
        finish(claim());
        var previous = claim();
        var context = prepare(previous);
        coreComplete(episode(2));
        finish(previous, context);
        finish(claim());
        var prepared = expireLeaseAfterCachedPreparation();

        var replay = claim();

        assertThat(replay.episode().episodeNo()).isEqualTo(3);
        assertThat(replay.analysisJobId()).isNotEqualTo(previous.analysisJobId());
        assertFreshPreparedClaim(replay, prepared.get());
        assertThat(items().getLast().getRecoveryAttemptCount()).isZero();
    }

    @Test
    @DisplayName("느린 실패 복구 준비 뒤 claim은 복구 예산과 토큰을 유지한 새 60초 lease를 반환한다")
    void recoveryClaimRenewsLeaseAfterCachedPreparation() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var previous = claim();
        failPreparedComparison(previous, FailureCode.COMPARISON_FAILED);
        var prepared = expireLeaseAfterCachedPreparation();

        var recovered = coordinator.claimRelationshipJob(recoveryRequest(true)).orElseThrow();

        assertThat(recovered.analysisJobId()).isNotEqualTo(previous.analysisJobId());
        assertFreshPreparedClaim(recovered, prepared.get());
        assertThat(items().getFirst().getRecoveryAttemptCount()).isEqualTo(1);
    }

    private record PreparedClaim(UUID leaseToken, int claimAttempts, LocalDateTime finishedAt) {}

    private java.util.concurrent.atomic.AtomicReference<PreparedClaim> expireLeaseAfterCachedPreparation() {
        var prepared = new java.util.concurrent.atomic.AtomicReference<PreparedClaim>();
        doAnswer(call -> {
            call.callRealMethod();
            AnalysisJob job = call.getArgument(0);
            LocalDateTime finishedAt = LocalDateTime.now();
            prepared.set(new PreparedClaim(job.getLeaseToken(), job.getClaimAttemptCount(), finishedAt));
            // Simulate preparation consuming the initial lease without sleeping or changing tokens.
            job.renewLease(finishedAt.minusSeconds(1));
            return null;
        }).when(graph).prepareCachedObservations(any(), any());
        return prepared;
    }

    private void assertFreshPreparedClaim(WorkerAnalysisJobPayload payload, PreparedClaim prepared) {
        assertThat(prepared).isNotNull();
        assertThat(payload.leaseToken()).isEqualTo(prepared.leaseToken());
        assertThat(payload.claimAttemptCount()).isEqualTo(prepared.claimAttempts()).isEqualTo(1);
        assertThat(payload.leaseExpiresAt()).isAfterOrEqualTo(prepared.finishedAt().plusSeconds(60));
        assertThat(payload.leaseExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(60));
        tx.executeWithoutResult(t -> {
            var job = em.find(AnalysisJob.class, payload.analysisJobId());
            assertThat(job.getStatus()).isEqualTo(AnalysisJobStatus.RUNNING);
            assertThat(job.getLeaseToken()).isEqualTo(payload.leaseToken());
            assertThat(job.getClaimAttemptCount()).isEqualTo(1);
            assertThat(job.getLeaseExpiresAt()).isCloseTo(payload.leaseExpiresAt(),
                    within(1, java.time.temporal.ChronoUnit.MILLIS));
        });
        // Check reclaim before heartbeat so an early heartbeat cannot hide an already expired claim.
        assertThat(coordinator.claimRelationshipJob(recoveryRequest(true))).isEmpty();
        var heartbeat = coordinator.renewRelationshipJobLease(payload.analysisJobId(), payload.leaseToken());
        assertThat(heartbeat.leaseToken()).isEqualTo(payload.leaseToken());
        assertThat(heartbeat.leaseExpiresAt()).isAfterOrEqualTo(payload.leaseExpiresAt());
        assertThat(tx.<Integer>execute(t ->
                em.find(AnalysisJob.class, payload.analysisJobId()).getClaimAttemptCount())).isEqualTo(1);
        verify(tokens, never()).releaseReservedForAnalysisJob(eq(payload.analysisJobId()), any());
        verify(graph).prepareCachedObservations(any(), any());
        finish(payload);
    }

    enum RecoveryRejection {
        MALFORMED_CACHE, EMPTY_CACHE, SOURCE_CHANGED, OUTSIDE_COHORT, USER_PAID, USER_PAID_JOB, WRONG_FAILURE,
        GENERATION_CHANGED, ACTIVE_CORE, SOURCE_PURGING, VALIDATION_PENDING, LATER_SUCCESS
    }

    @ParameterizedTest
    @EnumSource(RecoveryRejection.class)
    @DisplayName("복구가 현재 원문·대상·비용·순서·검수 경계를 우회하지 않는다")
    void recoveryDoesNotOverrideProtectedState(RecoveryRejection reason) {
        UUID one = episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, FailureCode.COMPARISON_FAILED);
        tx.executeWithoutResult(t -> {
            var item = em.find(RelationshipBackfillItem.class, items().getFirst().getId());
            var old = em.find(AnalysisJob.class, original.analysisJobId());
            var analysis = em.find(RelationshipGraphAnalysis.class, original.analysisJobId());
            switch (reason) {
                case MALFORMED_CACHE -> {
                    var malformed = analysis.getContext().deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) malformed)
                            .put("observations", "not-an-observation-array");
                    ReflectionTestUtils.setField(analysis, "context", malformed);
                }
                case EMPTY_CACHE -> {
                    var empty = analysis.getContext().deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) empty)
                            .set("observations", JsonNodeFactory.instance.arrayNode());
                    ReflectionTestUtils.setField(analysis, "context", empty);
                }
                case SOURCE_CHANGED -> em.find(Episode.class, one)
                        .updateContentStorage("replacement", "v2", storage.contentHash("변경된 원문"));
                case OUTSIDE_COHORT -> doReturn(false).when(manifest).matches(any());
                case USER_PAID -> {
                    ReflectionTestUtils.setField(item, "servicePaid", false);
                }
                case USER_PAID_JOB -> jdbc.update(
                        "update analysis_jobs set relationship_service_paid=false where id=?",
                        original.analysisJobId());
                case WRONG_FAILURE -> old.fail(AnalysisFailureCode.UNEXPECTED_ERROR, "복구 대상이 아닌 실패");
                case GENERATION_CHANGED -> ReflectionTestUtils.setField(
                        em.find(RelationshipBackfillWork.class, workId), "buildingGeneration", 2L);
                case ACTIVE_CORE -> em.persist(AnalysisJob.create(
                        old.getWork(), null, em.find(Episode.class, one), AnalysisJobType.SETTING_EXTRACTION));
                case SOURCE_PURGING -> em.persist(EpisodeSourcePurgeRequest.requestReplacement(
                        em.find(Episode.class, one), null, "retained"));
                case VALIDATION_PENDING -> doReturn(true).when(jobs)
                        .existsValidationStagingForEpisodes(List.of(one));
                case LATER_SUCCESS -> em.find(RelationshipBackfillItem.class, items().getLast().getId())
                        .succeeded();
            }
        });
        clearInvocations(storage);

        assertThat(coordinator.claimRelationshipJob(recoveryRequest(true))).isEmpty();
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(items().getFirst().getRecoveryAttemptCount()).isZero();
        assertThat(items().getFirst().getJobId()).isEqualTo(original.analysisJobId());
        assertThat(relationshipJobCount()).isEqualTo(1);
        verify(storage, never()).getText(anyString());
    }

    @Test
    @DisplayName("새 문맥 상한을 넘는 복구는 노드를 바꾸거나 다른 작품의 대기열을 막지 않는다")
    void oversizedRecoveryPreviewPreservesStateAndOtherWorkProgress() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        var original = claim();
        failPreparedComparison(original, FailureCode.COMPARISON_FAILED);
        UUID futureNode = tx.execute(t -> {
            var ara = em.createQuery(
                            "select n from RelationshipGraphNode n where n.workId=:work and n.name=:name",
                            RelationshipGraphNode.class)
                    .setParameter("work", workId).setParameter("name", "아라").getSingleResult();
            // A later observation already owns this identity. A read-only preview must not
            // move its first appearance into the failed chapter when rejecting the recovery.
            ReflectionTestUtils.setField(ara, "firstEpisodeNo", 10);
            for (int index = 0; index < 1535; index++)
                em.persist(RelationshipGraphNode.create(UUID.randomUUID(), workId, null, "보라", 1));
            return ara.getId();
        });
        clearInvocations(storage);
        assertThat(coordinator.claimRelationshipJob(recoveryRequest(true))).isEmpty();
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.BLOCKED);
        assertThat(items().getFirst().getRecoveryAttemptCount()).isZero();
        assertThat(items().getFirst().getJobId()).isEqualTo(original.analysisJobId());
        assertThat(relationshipJobCount()).isEqualTo(1);
        assertThat(tx.<AnalysisStatus>execute(t ->
                em.find(RelationshipGraphAnalysis.class, original.analysisJobId()).getStatus()))
                .isEqualTo(AnalysisStatus.FAILED);
        assertThat(tx.<Integer>execute(t ->
                em.find(RelationshipGraphNode.class, futureNode).getFirstEpisodeNo())).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "select count(*) from relationship_graph_nodes where work_id=?", Long.class, workId))
                .isEqualTo(1537);

        UUID otherWorkId = tx.execute(t -> {
            var work = Work.create(em.find(Member.class, owner), "다른 합성 백필", WorkGenre.FANTASY, null);
            em.persist(work);
            em.flush();
            em.detach(work);
            // Work uses generated IDs. Re-key this fresh fixture before adding dependants
            // so the blocked work is always inspected first by UUID.compareTo ordering.
            UUID laterWorkId = new UUID(Long.MAX_VALUE, Long.MAX_VALUE);
            assertThat(laterWorkId.compareTo(workId)).isPositive();
            jdbc.update("update works set id=? where id=?", laterWorkId, work.getId());
            work = em.find(Work.class, laterWorkId);
            var episode = Episode.create(work, null, 1, "1화", "synthetic/" + UUID.randomUUID(),
                    "v1", storage.contentHash(source), source.length());
            em.persist(episode);
            em.persist(RelationshipBackfillWork.create(work.getId(), manifest.campaignKey()));
            em.persist(RelationshipBackfillItem.create(episode, 1, true));
            return work.getId();
        });
        var other = coordinator.claimRelationshipJob(recoveryRequest(true)).orElseThrow();
        assertThat(other.workId()).isEqualTo(otherWorkId);
        assertThat(items().getFirst().getRecoveryAttemptCount()).isZero();
        verify(storage, never()).getText(anyString());
    }

    @Test
    @DisplayName("일시 정지 동안에도 관리 작품은 inline을 섞지 않고 새 회차를 대기열에 보존한다")
    void pauseKeepsManagedTail() {
        episode(1);
        coordinator.bootstrapRelationshipBackfill();
        ReflectionTestUtils.setField(coordinator, "enabled", false);
        coreComplete(episode(2));
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(items()).hasSize(2);
        ReflectionTestUtils.setField(coordinator, "enabled", true);
        finish(claim());
        finish(claim());
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
    }

    @Test
    @DisplayName("검수 중인 앞 회차를 건너뛰어 뒤 관계를 선점하지 않는다")
    void validationStagingWaits() {
        UUID one = episode(1);
        episode(2);
        coordinator.bootstrapRelationshipBackfill();
        doReturn(true).when(jobs).existsValidationStagingForEpisodes(List.of(one));
        assertThat(coordinator.claimRelationshipJob(request)).isEmpty();
        assertThat(items()).allMatch(i -> i.getState() == RelationshipBackfillItemState.WAITING);
        doReturn(false).when(jobs).existsValidationStagingForEpisodes(List.of(one));
        assertThat(claim().episode().episodeNo()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("부분 계약은 무관한 미해소 HOLD 누적을 제외하고 legacy 문맥 상한은 유지한다")
    void unresolvedHeldBacklogDoesNotBlockPartialPair(boolean partial) {
        seedUnresolvedHeldBacklog();
        var third = coordinator.claimRelationshipJob(partial ? partialRequest() : request).orElseThrow();
        var context = prepare(third);
        assertThat(context.observations()).hasSize(1);
        assertThat(context.observations().getFirst().sourceNodeRefs()).hasSize(1);
        assertThat(context.observations().getFirst().targetNodeRefs()).hasSize(1);
        if (partial) {
            assertThat(context.heldObservations()).isEmpty();
            assertThat(context.status()).isEqualTo(AnalysisStatus.PREPARED);
            finish(third, context);
            assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
            // Excluding old HOLDs from this input never consumes or deletes their source decisions.
            assertThat(graph.getRelationshipGraph(owner, workId, 3).partial()).isTrue();
        } else {
            assertThat(context.heldObservations()).hasSize(385);
            assertThat(context.status()).isEqualTo(AnalysisStatus.FAILED);
            tx.executeWithoutResult(t -> assertThat(em.find(RelationshipGraphAnalysis.class, third.analysisJobId())
                    .getFailureCode()).isEqualTo("INPUT_TOO_LARGE"));
        }
    }

    private void seedUnresolvedHeldBacklog() {
        source = "아라는 그녀와 이야기를 나눴다. 아라와 보라는 함께 길을 떠났다.";
        when(storage.getText(anyString())).thenReturn(source);
        episode(1); episode(2); episode(3);
        coordinator.bootstrapRelationshipBackfill();
        for (int count : new int[] {200, 185}) {
            var job = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
            var observations = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(index -> new Observation(
                    "O" + index, new Endpoint("아라", "아라"), new Endpoint(null, "그녀"),
                    "인물 미확정 합성 근거 " + index, List.of(new Evidence(0, source.length(), source)))).toList();
            var context = graph.prepareRelationshipAnalysis(job.analysisJobId(), job.leaseToken(),
                    new Prepare(1, storage.contentHash(source), observations, 0));
            var holds = observations.stream().map(o -> hold(o.ref())).toList();
            graph.completeRelationshipAnalysis(job.analysisJobId(), job.leaseToken(), new Complete(1, context.contextToken(), holds));
            coordinator.completeRelationshipJob(job.analysisJobId(), job.leaseToken(), null);
        }
    }

    private Decision hold(String ref) {
        return new Decision(List.of(ref), Operation.HOLD, null, null, null, null, null, null, null, null,
                TemporalScope.UNKNOWN, "인물 연결 미확정");
    }

    @Test
    @DisplayName("과거 partial 문맥의 무관한 HOLD 초과 실패는 새 문맥으로 한 번 복구하고 원본을 보존한다")
    void oversizedHistoricalPartialContextRecoversWithProjectedHistory() {
        seedUnresolvedHeldBacklog();
        var previous = claim();
        var failed = prepare(previous);
        assertThat(failed.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.heldObservations()).hasSize(385);
        restoreHistoricalPartialCapability(previous.analysisJobId());
        coordinator.failRelationshipJob(previous.analysisJobId(), previous.leaseToken(),
                new WorkerAnalysisJobFailRequest(AnalysisFailureCode.COMPARISON_VALIDATION_FAILED, "합성 실패"));
        assertThat(coordinator.claimRelationshipJob(recoveryRequest(true))).isEmpty();
        assertThat(items().getLast().getRecoveryAttemptCount()).isZero();
        var recovered = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        assertThat(recovered.analysisJobId()).isNotEqualTo(previous.analysisJobId());
        var context = graph.getRelationshipAnalysis(recovered.analysisJobId(), recovered.leaseToken()).orElseThrow();
        assertThat(context.status()).isEqualTo(AnalysisStatus.PREPARED);
        assertThat(context.heldObservations()).isEmpty();
        assertThat(context.supportsPartialResults()).isTrue();
        finish(recovered, context);
        assertThat(items().getLast().getRecoveryAttemptCount()).isEqualTo(1);
        assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        tx.executeWithoutResult(t -> {
            var cached = em.find(RelationshipGraphAnalysis.class, previous.analysisJobId());
            assertThat(cached.getContext().path("heldObservations")).hasSize(385);
            assertThat(cached.getFailureCode()).isEqualTo("INPUT_TOO_LARGE");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("부분 문맥은 양쪽 인물이 같은 R과 해소된 H만 포함하고 다른 쌍의 H는 저장에 남긴다")
    void contextProjectsExactPairsBeforeHistoryLimits(boolean partial) {
        source = "아라와 보라와 다라와 그녀는 함께 길을 떠났다.";
        when(storage.getText(anyString())).thenReturn(source);
        episode(1); episode(2); episode(3);
        coordinator.bootstrapRelationshipBackfill();
        var first = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        var evidence = List.of(new Evidence(0, source.length(), source));
        var input = new Prepare(1, storage.contentHash(source), List.of(
                new Observation("O1", new Endpoint("아라", "아라"), new Endpoint("보라", "보라"), "동행한다", evidence),
                new Observation("O2", new Endpoint("아라", "아라"), new Endpoint("다라", "다라"), "동행한다", evidence),
                new Observation("O3", new Endpoint("아라", "아라"), new Endpoint("보라", "보라"), "보류 근거", evidence),
                new Observation("O4", new Endpoint("아라", "아라"), new Endpoint("다라", "다라"), "보류 근거", evidence),
                new Observation("O5", new Endpoint("아라", "아라"), new Endpoint(null, "그녀"), "미해소 근거", evidence)), 0);
        var initial = graph.prepareRelationshipAnalysis(first.analysisJobId(), first.leaseToken(), input);
        graph.completeRelationshipAnalysis(first.analysisJobId(), first.leaseToken(), new Complete(1, initial.contextToken(),
                List.of(pairDecision(initial, "O1"), pairDecision(initial, "O2"), hold("O3"), hold("O4"), hold("O5"))));
        coordinator.completeRelationshipJob(first.analysisJobId(), first.leaseToken(), null);
        var second = coordinator.claimRelationshipJob(partial ? partialRequest() : request).orElseThrow();
        var context = prepare(second);
        assertThat(context.relationships()).hasSize(partial ? 1 : 2);
        assertThat(context.heldObservations()).hasSize(partial ? 1 : 3);
        if (partial) {
            assertThat(context.nodes()).extracting(ContextNode::name).containsExactlyInAnyOrder("아라", "보라");
            assertThat(context.heldObservations().getFirst().target().name()).isEqualTo("보라");
        }
        graph.completeRelationshipAnalysis(second.analysisJobId(), second.leaseToken(), new Complete(1,
                context.contextToken(), List.of(pairDecision(context, "O1"))));
        coordinator.completeRelationshipJob(second.analysisJobId(), second.leaseToken(), null);
        var third = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        var next = graph.prepareRelationshipAnalysis(third.analysisJobId(), third.leaseToken(), new Prepare(1,
                storage.contentHash(source), List.of(new Observation("O1", new Endpoint("아라", "아라"),
                new Endpoint("다라", "다라"), "함께 이동한다", evidence)), 0));
        assertThat(next.heldObservations()).hasSize(1);
        assertThat(next.heldObservations().getFirst().target().name()).isEqualTo("다라");
    }

    private enum PairContextScope { PARTIAL, LEGACY, HISTORICAL_PARTIAL }

    static java.util.stream.Stream<Arguments> relationshipCorrectionScopes() {
        return java.util.stream.Stream.of(
                Arguments.of(PairContextScope.PARTIAL, false, false, false),
                Arguments.of(PairContextScope.PARTIAL, false, true, false),
                Arguments.of(PairContextScope.PARTIAL, true, false, true),
                Arguments.of(PairContextScope.PARTIAL, true, true, true),
                Arguments.of(PairContextScope.LEGACY, false, false, true),
                Arguments.of(PairContextScope.HISTORICAL_PARTIAL, false, false, true));
    }

    @ParameterizedTest
    @MethodSource("relationshipCorrectionScopes")
    @DisplayName("새 부분 문맥은 독립 쌍 정정을 허용하고 같은 쌍 및 기존 고정 문맥의 충돌 보호는 유지한다")
    void correctionDependencyUsesFrozenPairScope(PairContextScope scope, boolean samePair, boolean delete, boolean conflicts) {
        source = "아라와 보라와 다라와 라라는 함께 길을 떠났다.";
        when(storage.getText(anyString())).thenReturn(source);
        UUID one = episode(1);
        episode(2);
        var old = tx.execute(t -> {
            var e = em.find(Episode.class, one);
            var job = AnalysisJob.create(e.getWork(), null, e, AnalysisJobType.SETTING_EXTRACTION);
            job.enableRelationshipAnalysis();
            em.persist(job);
            em.flush();
            job.claim("test", "test", LocalDateTime.now().plusMinutes(1));
            graph.beginRelationshipAnalysis(job);
            return new AbstractMap.SimpleEntry<>(job.getId(), job.getLeaseToken());
        });
        var initial = graph.prepareRelationshipAnalysis(old.getKey(), old.getValue(), disjointPairInput());
        graph.completeRelationshipAnalysis(old.getKey(), old.getValue(), new Complete(1, initial.contextToken(),
                List.of(pairDecision(initial, "O1"), pairDecision(initial, "O2"))));
        tx.executeWithoutResult(t -> {
            var job = em.find(AnalysisJob.class, old.getKey());
            graph.finalizeRelationshipAnalysis(job);
            job.succeed(null, 0, 0);
        });
        coordinator.bootstrapRelationshipBackfill();
        var first = coordinator.claimRelationshipJob(partialRequest()).orElseThrow();
        var rebuilt = graph.prepareRelationshipAnalysis(first.analysisJobId(), first.leaseToken(), disjointPairInput());
        graph.completeRelationshipAnalysis(first.analysisJobId(), first.leaseToken(), new Complete(1, rebuilt.contextToken(),
                List.of(pairDecision(rebuilt, "O1"), pairDecision(rebuilt, "O2"))));
        coordinator.completeRelationshipJob(first.analysisJobId(), first.leaseToken(), null);
        var second = coordinator.claimRelationshipJob(scope == PairContextScope.PARTIAL ? partialRequest() : request).orElseThrow();
        var context = prepare(second);
        assertThat(context.relationships()).hasSize(1);
        assertThat(context.nodes()).extracting(ContextNode::name).containsExactlyInAnyOrder("아라", "보라");
        if (scope == PairContextScope.PARTIAL) assertLegacyReaderAndMissingPairRevision(second);
        if (scope == PairContextScope.HISTORICAL_PARTIAL) restoreHistoricalPartialCapability(second.analysisJobId());
        assertThat(graph.getRelationshipAnalysis(second.analysisJobId(), second.leaseToken()).orElseThrow().contextToken())
                .isEqualTo(context.contextToken());
        var current = graph.getRelationshipGraph(owner, workId, null);
        var names = samePair ? List.of("아라", "보라") : List.of("다라", "라라");
        var pairNodes = current.nodes().stream().filter(n -> names.contains(n.name())).map(Node::id).toList();
        var edge = current.edges().stream().filter(e -> pairNodes.contains(e.sourceNodeId())
                && pairNodes.contains(e.targetNodeId())).findFirst().orElseThrow();
        if (delete) graph.deleteRelationshipEdge(owner, workId, edge.id(), edge.version());
        else graph.correctRelationshipEdge(owner, workId, edge.id(), new Correction(edge.version(), "사용자 정정"));
        var complete = new Complete(1, context.contextToken(), List.of(pairDecision(context, "O1")));
        if (conflicts) {
            assertThatThrownBy(() -> graph.getRelationshipAnalysis(second.analysisJobId(), second.leaseToken())).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> prepare(second)).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> graph.completeRelationshipAnalysis(second.analysisJobId(), second.leaseToken(), complete))
                    .isInstanceOf(AppException.class);
        } else {
            assertThat(graph.getRelationshipAnalysis(second.analysisJobId(), second.leaseToken()).orElseThrow().contextToken())
                    .isEqualTo(context.contextToken());
            graph.completeRelationshipAnalysis(second.analysisJobId(), second.leaseToken(), complete);
            graph.completeRelationshipAnalysis(second.analysisJobId(), second.leaseToken(), complete);
            coordinator.completeRelationshipJob(second.analysisJobId(), second.leaseToken(), null);
            assertThat(state().getState()).isEqualTo(RelationshipBackfillWorkState.COMPLETE);
        }
    }

    private void assertLegacyReaderAndMissingPairRevision(WorkerAnalysisJobPayload job) {
        tx.executeWithoutResult(t -> {
            var analysis = em.find(RelationshipGraphAnalysis.class, job.analysisJobId());
            var bindings = (com.fasterxml.jackson.databind.node.ObjectNode) analysis.getBindings().deepCopy();
            assertThat(bindings.path("dependencyRevision").asText())
                    .isNotEqualTo(bindings.path("pairDependencyRevision").asText());
            // An old server ignores the additive fields and validates only the unchanged broad hash.
            bindings.remove(List.of("dependencyPairScopeVersion", "pairDependencyRevision"));
            ReflectionTestUtils.setField(analysis, "bindings", bindings);
            em.flush();
            assertThat(graph.getRelationshipAnalysis(job.analysisJobId(), job.leaseToken())).isPresent();
            t.setRollbackOnly();
        });
        tx.executeWithoutResult(t -> {
            var analysis = em.find(RelationshipGraphAnalysis.class, job.analysisJobId());
            var bindings = (com.fasterxml.jackson.databind.node.ObjectNode) analysis.getBindings().deepCopy();
            bindings.remove("pairDependencyRevision");
            ReflectionTestUtils.setField(analysis, "bindings", bindings);
            em.flush();
            assertThatThrownBy(() -> graph.getRelationshipAnalysis(job.analysisJobId(), job.leaseToken()))
                    .isInstanceOf(AppException.class);
            t.setRollbackOnly();
        });
    }

    // Model the persisted pre-scope partial format without changing its token or broad dependency hash.
    private void restoreHistoricalPartialCapability(UUID jobId) {
        tx.executeWithoutResult(t -> {
            em.find(AnalysisJob.class, jobId).enableRelationshipPartialResults();
            var analysis = em.find(RelationshipGraphAnalysis.class, jobId);
            var context = analysis.getContext().deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) context).put("supportsPartialResults", true);
            ReflectionTestUtils.setField(analysis, "context", context);
            assertThat(analysis.getBindings().has("dependencyPairScopeVersion")).isFalse();
        });
    }

    private Prepare disjointPairInput() {
        var evidence = List.of(new Evidence(0, source.length(), source));
        return new Prepare(1, storage.contentHash(source), List.of(
                new Observation("O1", new Endpoint("아라", "아라"), new Endpoint("보라", "보라"), "함께 이동한다", evidence),
                new Observation("O2", new Endpoint("다라", "다라"), new Endpoint("라라", "라라"), "함께 이동한다", evidence)), 0);
    }
}
