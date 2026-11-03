package org.monitoring.catchholebackend.domain.relationship.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.AnalysisStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Axis;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Complete;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Correction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Decision;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Direction;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Edge;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Endpoint;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EpisodeRef;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Evidence;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Fail;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.FailureCode;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Graph;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Kind;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Observation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Operation;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Prepare;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.TemporalScope;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisJobLeaseService;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisRunStateService;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.member.entity.Member;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphAnalysisRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
class EpisodeRelationshipGraphIntegrationTest {
    @Autowired RelationshipGraphService service;
    @Autowired RelationshipHoldReviewGate holdGate;
    @Autowired org.monitoring.catchholebackend.domain.relationship.repository.RelationshipHoldClosureRepository holdClosures;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired AnalysisJobRepository jobs;
    @Autowired org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobClaimRepository claims;
    @Autowired RelationshipGraphAnalysisRepository analyses;
    @Autowired EpisodePurgeDataRepository purge;
    @MockitoBean AnalysisJobLeaseService leases;
    @MockitoBean ObjectStorageService storage;
    @MockitoSpyBean AnalysisRunStateService runState;
    private Long owner, other;
    private UUID workId;
    private final UUID lease = UUID.randomUUID();
    private final String source = "아라와 보라는 함께 길을 떠났다.";

    @BeforeEach
    void setup() {
        when(storage.contentHash(anyString())).thenCallRealMethod();
        when(storage.getText(anyString()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return source;
                        });
        when(leases.getRunningAnalysisJobForUpdate(any(), eq(lease)))
                .thenAnswer(invocation -> jobs.findById(invocation.getArgument(0)).orElseThrow());
        tx.executeWithoutResult(
                status -> {
                    Member a =
                            Member.register(
                                    UUID.randomUUID() + "@graph.test", "password", null, "작가");
                    em.persist(a);
                    owner = a.getId();
                    Member b =
                            Member.register(
                                    UUID.randomUUID() + "@graph.test", "password", null, "다른 작가");
                    em.persist(b);
                    other = b.getId();
                    Work work = Work.create(a, "합성 관계 검증", WorkGenre.FANTASY, null);
                    em.persist(work);
                    workId = work.getId();
                });
    }

    private UUID job(int no) {
        return job(no, false);
    }

    private UUID job(int no, boolean ordered) {
        return tx.execute(
                status -> {
                    Work work = em.find(Work.class, workId);
                    Episode episode =
                            em.createQuery(
                                            "select e from Episode e where e.work.id=:work and"
                                                    + " e.episodeNo=:no",
                                            Episode.class)
                                    .setParameter("work", workId)
                                    .setParameter("no", no)
                                    .getResultStream()
                                    .findFirst()
                                    .orElseGet(
                                            () -> {
                                                Episode e =
                                                        Episode.create(
                                                                work,
                                                                null,
                                                                no,
                                                                no + "화",
                                                                "source/" + UUID.randomUUID(),
                                                                "v1",
                                                                storage.contentHash(source),
                                                                source.length());
                                                em.persist(e);
                                                return e;
                                            });
                    AnalysisJob job =
                            AnalysisJob.create(
                                    work, null, episode, AnalysisJobType.SETTING_EXTRACTION);
                    job.enableRelationshipAnalysis();
                    if (ordered)
                        job.initializeOrderedRun(
                                UUID.randomUUID(),
                                1,
                                0,
                                null,
                                new ObjectMapper().createObjectNode());
                    em.persist(job);
                    em.flush();
                    service.beginRelationshipAnalysis(job);
                    return job.getId();
                });
    }

    private Context prepare(UUID id) {
        return service.prepareRelationshipAnalysis(
                id,
                lease,
                new Prepare(
                        1,
                        storage.contentHash(source),
                        List.of(
                                new Observation(
                                        "O1",
                                        new Endpoint("아라", "아라"),
                                        new Endpoint("보라", "보라"),
                                        "함께 여행한다",
                                        List.of(
                                                new Evidence(
                                                        0,
                                                        source.codePointCount(0, source.length()),
                                                        source)))),
                        0));
    }

    private Decision action(Context context, Operation operation, Kind kind, String summary) {
        String relationship =
                operation == Operation.ADD
                        ? null
                        : context.relationships().stream()
                                .filter(r -> r.kind() == kind)
                                .findFirst()
                                .orElseThrow()
                                .ref();
        String a = context.observations().getFirst().sourceNodeRefs().getFirst(),
                b = context.observations().getFirst().targetNodeRefs().getFirst();
        return new Decision(
                List.of("O1"),
                operation,
                relationship,
                a,
                b,
                Axis.SOCIAL,
                kind,
                "동행",
                summary,
                Direction.MUTUAL,
                TemporalScope.PRESENT,
                null);
    }

    private void apply(UUID id, Context context, Decision... decisions) {
        service.completeRelationshipAnalysis(
                id, lease, new Complete(1, context.contextToken(), List.of(decisions)));
        tx.executeWithoutResult(
                status -> service.finalizeRelationshipAnalysis(jobs.findById(id).orElseThrow()));
    }

    @Test
    @DisplayName("원문 추출 실패의 계약을 보존하고 실패 뒤 첫 관계도 완료 구간으로 오인하지 않는다")
    void failedExtractionRetainsFrozenCapabilityAndDoesNotVerifyLaterFirstEdge() {
        UUID first = job(1);
        tx.executeWithoutResult(t -> jobs.findById(first).orElseThrow().enableRelationshipPartialResults());
        service.failRelationshipAnalysis(first, lease, new Fail(1, storage.contentHash(source), null, FailureCode.EXTRACTION_FAILED));
        Context failed = service.getRelationshipAnalysis(first, lease).orElseThrow();
        assertThat(failed.supportsPartialResults()).isTrue();
        assertThat(failed.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.failures()).isEmpty();
        UUID second = job(2);
        Context prepared = prepare(second);
        apply(second, prepared, action(prepared, Operation.ADD, Kind.COMPANIONSHIP, "함께 이동한다"));
        Graph result = service.getRelationshipGraph(owner, workId, 2);
        assertThat(result.updateIncomplete()).isTrue();
        assertThat(result.incompleteEpisodeNos()).containsExactly(1);
        assertThat(result.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.updateIncomplete()).isTrue();
            assertThat(edge.lastVerifiedEpisodeNo()).isZero();
        });
    }

    @Test
    @DisplayName("유효한 부분 실패가 있는 작품만 새 부분 완료 Worker 지원을 요구한다")
    void ordinaryClaimRequiresPartialCapabilityOnlyForLivePartialPrefix() {
        tx.executeWithoutResult(t -> em.createQuery("select j from AnalysisJob j where j.status=:pending", AnalysisJob.class)
                .setParameter("pending", org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING)
                .getResultList().forEach(AnalysisJob::cancelForWorkPurge));
        UUID first = job(1);
        tx.executeWithoutResult(t -> jobs.findById(first).orElseThrow().enableRelationshipPartialResults());
        Context context = prepare(first);
        service.completeRelationshipAnalysis(first, lease, new Complete(1, context.contextToken(), List.of(),
                List.of(new org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.GroupFailure(
                        List.of("O1"), "RELATIONSHIP_ENDPOINTS_UNGROUNDED"))));
        tx.executeWithoutResult(t -> {
            var completed = jobs.findById(first).orElseThrow();
            service.finalizeRelationshipAnalysis(completed);
            completed.succeed(null, 0, 0);
        });
        UUID next = job(2);
        var legacy = new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest(
                "synthetic", null, java.util.Set.of(AnalysisJobType.SETTING_EXTRACTION), null, false,
                null, false, false, false, true, false, false);
        var modern = new org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest(
                "synthetic", null, java.util.Set.of(AnalysisJobType.SETTING_EXTRACTION), null, false,
                null, false, false, false, true, false, true);
        var legacyResult = tx.execute(t -> claims.findClaimableJob(legacy));
        assertThat(legacyResult).isEmpty();
        UUID modernId = tx.execute(t -> claims.findClaimableJob(modern).orElseThrow().getId());
        assertThat(modernId).isEqualTo(next);
        tx.executeWithoutResult(t -> analyses.findById(first).orElseThrow().invalidate());
        UUID legacyId = tx.execute(t -> claims.findClaimableJob(legacy).orElseThrow().getId());
        assertThat(legacyId).isEqualTo(next);
    }

    @Test
    void episodeCutoffEndAndEvidenceAreHistoricalAndRetryIsIdempotent() {
        UUID first = job(1);
        Context initial = prepare(first);
        assertThat(prepare(first)).isEqualTo(initial);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(2);
        Context next = prepare(second);
        apply(second, next, action(next, Operation.END, Kind.COMPANIONSHIP, "동행을 끝냈다"));
        Graph past = service.getRelationshipGraph(owner, workId, 1),
                latest = service.getRelationshipGraph(owner, workId, null);
        assertThat(past.edges())
                .singleElement()
                .extracting(Edge::status)
                .isEqualTo(EdgeStatus.ACTIVE);
        assertThat(latest.edges())
                .singleElement()
                .extracting(Edge::status)
                .isEqualTo(EdgeStatus.ENDED);
        assertThat(
                        service.getRelationshipEdge(owner, workId, past.edges().getFirst().id(), 1)
                                .evidence())
                .hasSize(1)
                .allSatisfy(e -> assertThat(e.episodeNo()).isEqualTo(1));
        assertThatThrownBy(() -> service.getRelationshipGraph(other, workId, null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.getRelationshipGraph(owner, workId, 3))
                .isInstanceOf(AppException.class);
        tx.executeWithoutResult(
                status ->
                        service.finalizeRelationshipAnalysis(jobs.findById(second).orElseThrow()));
        assertThat(service.getRelationshipGraph(owner, workId, null).edges()).hasSize(1);
    }

    @Test
    void correctionsAreVersionedAndDeletionSurvivesReanalysis() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        Edge edge = service.getRelationshipGraph(owner, workId, 1).edges().getFirst();
        service.correctRelationshipEdge(
                owner, workId, edge.id(), new Correction(edge.version(), "오해를 바로잡았다"));
        assertThatThrownBy(
                        () ->
                                service.deleteRelationshipEdge(
                                        owner, workId, edge.id(), edge.version()))
                .isInstanceOf(AppException.class);
        service.deleteRelationshipEdge(owner, workId, edge.id(), edge.version() + 1);
        UUID retry = job(1);
        Context context = prepare(retry);
        apply(retry, context, action(context, Operation.ADD, Kind.COMPANIONSHIP, "다시 분석된 동행"));
        assertThat(service.getRelationshipGraph(owner, workId, 1).edges()).isEmpty();
    }

    @Test
    void failureBeforePrepareDoesNotNeedRelationshipCompletion() {
        UUID id = job(1);
        assertThat(service.getRelationshipAnalysis(id, lease)).isEmpty();
        service.failRelationshipAnalysis(
                id,
                lease,
                new Fail(1, storage.contentHash(source), null, FailureCode.EXTRACTION_FAILED));
        tx.executeWithoutResult(
                status -> service.finalizeRelationshipAnalysis(jobs.findById(id).orElseThrow()));
        assertThat(service.getRelationshipGraph(owner, workId, null).analysisStatus())
                .isEqualTo("FAILED");
        assertThat(service.getRelationshipAnalysis(id, lease).orElseThrow().status())
                .isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    void isolatedFailureKeepsOnlyApprovedDiagnosticsAndPreservesFirstReport() {
        UUID id = job(1);
        var initialStatus = jobs.findById(id).orElseThrow().getStatus();
        var json = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var request = json.readValue("""
                {"formatVersion":1,"sourceHash":"%s","contextToken":null,"failureCode":"EXTRACTION_FAILED",
                 "failureDiagnostics":{"schemaVersion":1,"origin":"WORKER","stage":"RELATIONSHIP_EXTRACTION",
                 "reasonCode":"RELATIONSHIP_DISPLAY_REFERENCE_LEAK","rawError":"UNAPPROVED_ERROR",
                 "excerpts":[{"kind":"RESPONSE","field":"relationshipSummary","text":"O1과 함께 이동한다."}]}}
                """.formatted(storage.contentHash(source)), Fail.class);
        service.failRelationshipAnalysis(id, lease, request);
        // An old worker retry cannot erase the first report's diagnostics.
        service.failRelationshipAnalysis(id, lease, new Fail(1, storage.contentHash(source), null, FailureCode.EXTRACTION_FAILED));
        var stored = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(analyses.findById(id).orElseThrow());
        assertThat(stored.path("failureDiagnostics").path("reasonCode").asText()).isEqualTo("RELATIONSHIP_DISPLAY_REFERENCE_LEAK");
        assertThat(stored.path("failureDiagnostics").path("excerpts").get(0).path("text").asText()).isEqualTo("O1과 함께 이동한다.");
        assertThat(stored.toString()).doesNotContain("UNAPPROVED_ERROR");
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(initialStatus);
    }

    @Test
    void earlierCompletionInvalidatesLaterPreparedContext() {
        UUID first = job(1), second = job(2);
        Context a = prepare(first), b = prepare(second);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        assertThatThrownBy(
                        () ->
                                service.completeRelationshipAnalysis(
                                        second,
                                        lease,
                                        new Complete(
                                                1,
                                                b.contextToken(),
                                                List.of(
                                                        action(
                                                                b,
                                                                Operation.ADD,
                                                                Kind.COMPANIONSHIP,
                                                                "함께 여행한다")))))
                .isInstanceOf(AppException.class);
        assertThat(service.getRelationshipGraph(owner, workId, null).analysisStatus())
                .isEqualTo("INVALIDATED");
        assertThat(service.getRelationshipGraph(owner, workId, null).availableEpisodes())
                .extracting(EpisodeRef::episodeNo)
                .containsExactly(1);
    }

    @Test
    void earlierCompletionInvalidatesAlreadyAppliedLaterManualJob() {
        UUID first = job(1), second = job(2);
        Context b = prepare(second), a = prepare(first);
        apply(second, b, action(b, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        assertThat(service.getRelationshipGraph(owner, workId, null).availableEpisodes())
                .extracting(EpisodeRef::episodeNo)
                .containsExactly(1);
        assertThat(analyses.findById(second).orElseThrow().getStatus())
                .isEqualTo(AnalysisStatus.INVALIDATED);
        UUID retry = job(2);
        Context refreshed = prepare(retry);
        assertThat(refreshed.observations().getFirst().sourceNodeRefs()).hasSize(1);
        assertThat(refreshed.observations().getFirst().targetNodeRefs()).hasSize(1);
        assertThat(refreshed.relationships()).hasSize(1);
    }

    @Test
    @DisplayName("2화부터 준비해도 1화가 같은 인물 ID를 재사용하고 미래 프로필 연결을 숨긴다")
    void reverseManualPreparationReusesNodesWithoutFutureIdentityLeak() {
        UUID first = job(1), second = job(2);
        Context future = prepare(second);
        UUID ara =
                boundNodeId(second, future.observations().getFirst().sourceNodeRefs().getFirst());
        UUID bora =
                boundNodeId(second, future.observations().getFirst().targetNodeRefs().getFirst());
        tx.executeWithoutResult(
                status -> {
                    Work work = em.find(Work.class, workId);
                    WorkCharacter character =
                            WorkCharacter.create(
                                    work,
                                    "미래의 현재 프로필 이름",
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
                    em.find(RelationshipGraphNode.class, ara).link(character.getId(), 2);
                    em.persist(
                            RelationshipGraphNode.create(
                                    UUID.randomUUID(), workId, null, "미래에만 등장한 인물", 2));
                });

        Context past = prepare(first);
        assertThat(past.nodes()).extracting(n -> n.name()).containsExactlyInAnyOrder("아라", "보라");
        assertThat(past.nodes()).allSatisfy(n -> assertThat(n.profileRegistered()).isFalse());
        assertThat(boundNodeId(first, past.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(ara);
        assertThat(boundNodeId(first, past.observations().getFirst().targetNodeRefs().getFirst()))
                .isEqualTo(bora);
        assertThat(analyses.findById(second).orElseThrow().getContextToken())
                .isEqualTo(future.contextToken());
        assertThat(analyses.findById(second).orElseThrow().getStatus())
                .isEqualTo(AnalysisStatus.PREPARED);
        assertThat(
                        boundNodeId(
                                second,
                                future.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(ara);
        apply(first, past, action(past, Operation.ADD, Kind.COMPANIONSHIP, "1화 동행"));
        assertThatThrownBy(() -> service.getRelationshipAnalysis(second, lease))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> prepare(second)).isInstanceOf(AppException.class);
        Graph firstGraph = service.getRelationshipGraph(owner, workId, 1);
        assertThat(firstGraph.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(ara, bora);
        assertThat(firstGraph.nodes()).allSatisfy(n -> assertThat(n.profileRegistered()).isFalse());
        assertThat(
                        service.getRelationshipEdge(
                                        owner, workId, firstGraph.edges().getFirst().id(), 1)
                                .evidence())
                .allSatisfy(e -> assertThat(e.episodeNo()).isEqualTo(1));

        UUID retry = job(2);
        Context refreshed = prepare(retry);
        assertThat(refreshed.observations().getFirst().sourceNodeRefs()).hasSize(1);
        assertThat(refreshed.observations().getFirst().targetNodeRefs()).hasSize(1);
        assertThat(refreshed.relationships()).hasSize(1);
        assertThat(refreshed.nodes())
                .filteredOn(n -> n.name().equals("아라"))
                .singleElement()
                .satisfies(n -> assertThat(n.profileRegistered()).isTrue());
        apply(retry, refreshed, action(refreshed, Operation.UPDATE, Kind.COMPANIONSHIP, "2화 동행"));
        Graph latest = service.getRelationshipGraph(owner, workId, 2);
        assertThat(latest.edges().getFirst().id()).isEqualTo(firstGraph.edges().getFirst().id());
        assertThat(latest.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(ara, bora);
        assertThat(latest.nodes()).extracting(n -> n.name()).containsExactlyInAnyOrder("아라", "보라");
        assertThat(service.getRelationshipGraph(owner, workId, 1).nodes())
                .allSatisfy(n -> assertThat(n.profileRegistered()).isFalse());
        tx.executeWithoutResult(
                status -> {
                    em.clear();
                    assertThat(em.find(RelationshipGraphNode.class, ara).getFirstEpisodeNo())
                            .isEqualTo(1);
                    assertThat(
                                    em.find(RelationshipGraphNode.class, ara)
                                            .getCharacterLinkedEpisodeNo())
                            .isEqualTo(2);
                });
    }

    @Test
    @DisplayName("역순 회차 재분석도 기존 관계 ID와 사용자 정정·삭제를 보존한다")
    void reverseManualReanalysisKeepsCorrectionsAndSuppression() {
        UUID second = job(2);
        Context later = prepare(second);
        apply(
                second,
                later,
                action(later, Operation.ADD, Kind.COMPANIONSHIP, "동행한다"),
                action(later, Operation.ADD, Kind.FRIENDSHIP, "친구다"));
        Graph original = service.getRelationshipGraph(owner, workId, 2);
        Edge companion =
                original.edges().stream()
                        .filter(e -> e.kind() == Kind.COMPANIONSHIP)
                        .findFirst()
                        .orElseThrow();
        Edge friend =
                original.edges().stream()
                        .filter(e -> e.kind() == Kind.FRIENDSHIP)
                        .findFirst()
                        .orElseThrow();
        service.correctRelationshipEdge(
                owner, workId, companion.id(), new Correction(companion.version(), "사용자가 정정한 동행"));
        service.deleteRelationshipEdge(owner, workId, friend.id(), friend.version());

        UUID first = job(1);
        Context earlier = prepare(first);
        apply(
                first,
                earlier,
                action(earlier, Operation.ADD, Kind.COMPANIONSHIP, "1화 동행"),
                action(earlier, Operation.ADD, Kind.FRIENDSHIP, "1화 친구"));
        Graph past = service.getRelationshipGraph(owner, workId, 1);
        assertThat(past.edges())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.id()).isEqualTo(companion.id());
                            assertThat(e.summary()).isEqualTo("사용자가 정정한 동행");
                        });
        assertThat(past.nodes())
                .extracting(n -> n.id())
                .containsExactlyInAnyOrderElementsOf(
                        original.nodes().stream().map(n -> n.id()).toList());

        UUID retry = job(2);
        Context refreshed = prepare(retry);
        apply(
                retry,
                refreshed,
                action(refreshed, Operation.UPDATE, Kind.COMPANIONSHIP, "2화 동행"),
                action(refreshed, Operation.ADD, Kind.FRIENDSHIP, "다시 분석한 친구"));
        assertThat(service.getRelationshipGraph(owner, workId, 2).edges())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.id()).isEqualTo(companion.id());
                            assertThat(e.summary()).isEqualTo("사용자가 정정한 동행");
                        });
    }

    @Test
    @DisplayName("미래에 만들어진 동명 노드가 여러 개면 병합하거나 새 노드를 만들지 않는다")
    void ambiguousFutureNodesRemainUnresolved() {
        UUID second = job(2);
        Context future = prepare(second);
        UUID original =
                boundNodeId(second, future.observations().getFirst().sourceNodeRefs().getFirst());
        UUID duplicate = UUID.randomUUID();
        tx.executeWithoutResult(
                status ->
                        em.persist(RelationshipGraphNode.create(duplicate, workId, null, "아라", 2)));
        Context past = prepare(job(1));
        assertThat(past.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(past.nodes()).extracting(n -> n.name()).containsExactly("보라");
        tx.executeWithoutResult(
                status -> {
                    assertThat(em.find(RelationshipGraphNode.class, original).getFirstEpisodeNo())
                            .isEqualTo(2);
                    assertThat(em.find(RelationshipGraphNode.class, duplicate).getFirstEpisodeNo())
                            .isEqualTo(2);
                    assertThat(
                                    em.createQuery(
                                                    "select count(n) from RelationshipGraphNode n"
                                                            + " where n.workId=:work",
                                                    Long.class)
                                            .setParameter("work", workId)
                                            .getSingleResult())
                            .isEqualTo(3);
                });
    }

    @Test
    @DisplayName("현재 고정 인물 ID와 다른 미래의 동명 프로필은 이름만으로 재사용하지 않는다")
    void futureProfileConflictingWithFrozenIdentityRemainsUnresolved() {
        UUID second = job(2);
        Context future = prepare(second);
        UUID nodeId =
                boundNodeId(second, future.observations().getFirst().sourceNodeRefs().getFirst());
        tx.executeWithoutResult(
                status -> {
                    WorkCharacter character =
                            WorkCharacter.create(
                                    em.find(Work.class, workId),
                                    "아라",
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
                    em.find(RelationshipGraphNode.class, nodeId).link(character.getId(), 2);
                });
        var input = new ObjectMapper().createObjectNode();
        input.putArray("characters")
                .addObject()
                .put("name", "아라")
                .put("actualCharacterId", UUID.randomUUID().toString());
        doReturn(input).when(runState).getInputState(any());
        Context past = prepare(job(1, true));
        assertThat(past.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(past.nodes()).extracting(n -> n.name()).containsExactly("보라");
        tx.executeWithoutResult(
                status -> {
                    assertThat(em.find(RelationshipGraphNode.class, nodeId).getFirstEpisodeNo())
                            .isEqualTo(2);
                    assertThat(
                                    em.createQuery(
                                                    "select count(n) from RelationshipGraphNode n"
                                                            + " where n.workId=:work",
                                                    Long.class)
                                            .setParameter("work", workId)
                                            .getSingleResult())
                            .isEqualTo(2);
                });

        // A manual observation can make this node cutoff-safe. A later frozen identity
        // conflict must still be rejected after it moves into the existing-node branch.
        UUID manual = job(1);
        Context named = prepare(manual);
        assertThat(named.observations().getFirst().sourceNodeRefs()).hasSize(1);
        assertThat(boundNodeId(manual, named.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(nodeId);
        Context retried = prepare(job(1, true));
        assertThat(retried.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(retried.nodes()).extracting(n -> n.name()).containsExactly("보라");
    }

    private UUID boundNodeId(UUID jobId, String ref) {
        return UUID.fromString(
                analyses.findById(jobId)
                        .orElseThrow()
                        .getBindings()
                        .path("nodes")
                        .path(ref)
                        .path("id")
                        .asText());
    }

    @Test
    @DisplayName("앞 회차 미등록 인물은 뒤 회차의 확정 프로필에 연결되고 과거에는 미등록으로 남는다")
    void earlierStandaloneNodeLinksToLaterConfirmedProfile() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID nodeId =
                boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID second = job(2);
        Context later = prepare(second);
        UUID characterId = character("아라");
        confirmCandidate(second, characterId, "아라");
        apply(second, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "함께 여행한다"));

        assertProfileLink(nodeId, characterId, 1, 2);
        assertThat(service.getRelationshipGraph(owner, workId, 1).nodes())
                .filteredOn(n -> n.id().equals(nodeId))
                .singleElement()
                .satisfies(n -> assertThat(n.profileRegistered()).isFalse());
        assertThat(service.getRelationshipGraph(owner, workId, 2).nodes())
                .filteredOn(n -> n.id().equals(nodeId))
                .singleElement()
                .satisfies(n -> assertThat(n.profileRegistered()).isTrue());
        tx.executeWithoutResult(
                status ->
                        service.finalizeRelationshipAnalysis(jobs.findById(second).orElseThrow()));
        assertProfileLink(nodeId, characterId, 1, 2);
    }

    @Test
    @DisplayName("관계 반영 뒤 수동 확정한 프로필은 다음 회차의 고정 인물 정보로 연결한다")
    void frozenKnownIdentityLinksAfterEarlierManualConfirmation() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID nodeId =
                boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID characterId = character("현재 프로필의 다른 이름");
        confirmCandidate(first, characterId, "아라");
        frozenCharacter("아라", characterId);
        UUID second = job(2, true);
        Context later = prepare(second);
        assertProfileLink(nodeId, null, 1, null);
        // The saved prepare input is authoritative even if a later reader supplies other state.
        frozenCharacter("아라", character("또 다른 최신 프로필"));
        apply(second, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "함께 여행한다"));

        assertProfileLink(nodeId, characterId, 1, 2);
        assertThat(service.getRelationshipGraph(owner, workId, 1).nodes())
                .filteredOn(n -> n.id().equals(nodeId))
                .singleElement()
                .satisfies(n -> assertThat(n.profileRegistered()).isFalse());
        assertThat(service.getRelationshipGraph(owner, workId, 2).nodes())
                .filteredOn(n -> n.id().equals(nodeId))
                .singleElement()
                .satisfies(
                        n -> {
                            assertThat(n.profileRegistered()).isTrue();
                            assertThat(n.name()).isEqualTo("아라");
                        });
    }

    @Test
    @DisplayName("고정 인물과 이번 확정 후보의 ID가 다르면 미등록 노드를 연결하지 않는다")
    void conflictingFrozenAndConfirmedIdentitiesDoNotLink() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID nodeId =
                boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        frozenCharacter("아라", character("아라"));
        UUID second = job(2, true);
        Context later = prepare(second);
        confirmCandidate(second, character("아라"), "아라");
        apply(second, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "함께 여행한다"));
        assertProfileLink(nodeId, null, 1, null);
    }

    @Test
    @DisplayName("고정 인물 ID가 다른 작품의 프로필이면 미등록 노드에 연결하지 않는다")
    void frozenIdentityFromAnotherWorkDoesNotLink() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID nodeId =
                boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID foreignCharacter =
                tx.execute(
                        status -> {
                            Work foreignWork =
                                    Work.create(
                                            em.find(Member.class, other),
                                            "다른 작품",
                                            WorkGenre.FANTASY,
                                            null);
                            em.persist(foreignWork);
                            WorkCharacter character =
                                    WorkCharacter.create(
                                            foreignWork,
                                            "아라",
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
                            return character.getId();
                        });
        frozenCharacter("아라", foreignCharacter);
        UUID second = job(2, true);
        Context later = prepare(second);
        apply(second, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "함께 여행한다"));
        assertProfileLink(nodeId, null, 1, null);
    }

    @Test
    @DisplayName("동명 노드가 여러 개면 확정 프로필이 하나여도 임의로 연결하지 않는다")
    void ambiguousStandaloneNodesDoNotLinkToSingleConfirmedProfile() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID original =
                boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID duplicate = UUID.randomUUID();
        tx.executeWithoutResult(
                status ->
                        em.persist(RelationshipGraphNode.create(duplicate, workId, null, "아라", 1)));
        UUID second = job(2);
        Context later = prepare(second);
        confirmCandidate(second, character("아라"), "아라");
        apply(
                second,
                later,
                new Decision(
                        List.of("O1"),
                        Operation.HOLD,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        TemporalScope.UNKNOWN,
                        "동명 인물 구분이 필요하다"));
        assertProfileLink(original, null, 1, null);
        assertProfileLink(duplicate, null, 1, null);
    }

    private UUID character(String name) {
        return tx.execute(
                status -> {
                    WorkCharacter character =
                            WorkCharacter.create(
                                    em.find(Work.class, workId),
                                    name,
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
                    return character.getId();
                });
    }

    private void confirmCandidate(UUID jobId, UUID characterId, String name) {
        tx.executeWithoutResult(
                status -> {
                    AnalysisJob job = jobs.findById(jobId).orElseThrow();
                    ObjectMapper json = new ObjectMapper();
                    SettingCandidate candidate =
                            SettingCandidate.create(
                                    job.getWork(),
                                    job.getEpisode(),
                                    UUID.randomUUID(),
                                    job,
                                    SettingEntityType.CHARACTER,
                                    name,
                                    name,
                                    characterId,
                                    SettingCandidateMatchStatus.MATCHED,
                                    "profile.설명",
                                    "여행자",
                                    SettingValueType.STRING,
                                    json.createObjectNode().put("value", "여행자"),
                                    json.createArrayNode(),
                                    BigDecimal.ONE,
                                    json.createObjectNode());
                    candidate.confirm();
                    em.persist(candidate);
                });
    }

    private void frozenCharacter(String name, UUID characterId) {
        var input = new ObjectMapper().createObjectNode();
        input.putArray("characters")
                .addObject()
                .put("name", name)
                .put("actualCharacterId", characterId.toString());
        doReturn(input).when(runState).getInputState(any());
    }

    private void assertProfileLink(
            UUID nodeId, UUID characterId, int firstEpisode, Integer linkedEpisode) {
        tx.executeWithoutResult(
                status -> {
                    em.clear();
                    RelationshipGraphNode node = em.find(RelationshipGraphNode.class, nodeId);
                    assertThat(node.getFirstEpisodeNo()).isEqualTo(firstEpisode);
                    assertThat(node.getCharacterId()).isEqualTo(characterId);
                    assertThat(node.getCharacterLinkedEpisodeNo()).isEqualTo(linkedEpisode);
                });
    }

    @Test
    void resolvedHoldIsNotOfferedAgainAndNeverNeedsUserReview() {
        UUID first = job(1);
        Context a = prepare(first);
        apply(
                first,
                a,
                new Decision(
                        List.of("O1"),
                        Operation.HOLD,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        TemporalScope.UNKNOWN,
                        "관계를 더 관찰해야 한다"));
        assertThat(service.getRelationshipGraph(owner, workId, 1).analysisStatus())
                .isEqualTo("PARTIAL");
        UUID second = job(2);
        Context b = prepare(second);
        assertThat(b.heldObservations()).hasSize(1);
        Decision add = action(b, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다");
        apply(
                second,
                b,
                new Decision(
                        List.of("O1", "H1"),
                        add.operation(),
                        null,
                        add.sourceNodeRef(),
                        add.targetNodeRef(),
                        add.axis(),
                        add.kind(),
                        add.label(),
                        add.summary(),
                        add.direction(),
                        add.temporalScope(),
                        null));
        assertThat(service.getRelationshipGraph(owner, workId, 2).analysisStatus())
                .isEqualTo("COMPLETED");
        assertThat(service.getRelationshipGraph(owner, workId, 1).analysisStatus())
                .isEqualTo("PARTIAL");
        UUID third = job(3);
        assertThat(prepare(third).heldObservations()).isEmpty();
    }

    @Test
    void userCorrectionAfterPrepareRejectsStaleComparison() {
        UUID first = job(1);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(2);
        Context b = prepare(second);
        Edge edge = service.getRelationshipGraph(owner, workId, 1).edges().getFirst();
        service.correctRelationshipEdge(
                owner, workId, edge.id(), new Correction(edge.version(), "사용자가 바로잡은 설명"));
        assertThatThrownBy(() -> service.getRelationshipAnalysis(second, lease))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> prepare(second)).isInstanceOf(AppException.class);
        verify(storage, times(2)).getText(anyString());
        assertThatThrownBy(
                        () ->
                                service.completeRelationshipAnalysis(
                                        second,
                                        lease,
                                        new Complete(
                                                1,
                                                b.contextToken(),
                                                List.of(
                                                        action(
                                                                b,
                                                                Operation.UPDATE,
                                                                Kind.COMPANIONSHIP,
                                                                "새 설명")))))
                .isInstanceOf(AppException.class);
    }

    @Test
    void duplicateKnownNameNeverReusesSingleProvisionalNode() {
        UUID first = job(1);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(2, true);
        ObjectMapper json = new ObjectMapper();
        var input = json.createObjectNode();
        var characters = input.putArray("characters");
        characters
                .addObject()
                .put("name", "아라")
                .put("actualCharacterId", UUID.randomUUID().toString());
        characters
                .addObject()
                .put("name", "아라")
                .put("actualCharacterId", UUID.randomUUID().toString());
        doReturn(input).when(runState).getInputState(any());
        Context context = prepare(second);
        assertThat(context.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(context.relationships()).isEmpty();
    }

    @Test
    void invalidatedEarlierSourceAlsoWithholdsLaterHeldContext() {
        UUID first = job(1);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(2);
        Context b = prepare(second);
        apply(
                second,
                b,
                new Decision(
                        List.of("O1"),
                        Operation.HOLD,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        TemporalScope.UNKNOWN,
                        "새 근거를 기다린다"));
        tx.executeWithoutResult(
                status -> {
                    Episode episode = jobs.findById(first).orElseThrow().getEpisode();
                    episode.updateContentStorage(
                            episode.getContentS3Key(), "v2", episode.getContentHash());
                });
        UUID third = job(3);
        Context c = prepare(third);
        assertThat(c.relationships()).isEmpty();
        assertThat(c.heldObservations()).isEmpty();
    }

    @Test
    void reversedSymmetricReanalysisKeepsNodeIdsAndNamesAligned() {
        UUID first = job(1);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        Graph before = service.getRelationshipGraph(owner, workId, 1);
        UUID retry = job(1);
        Context b =
                service.prepareRelationshipAnalysis(
                        retry,
                        lease,
                        new Prepare(
                                1,
                                storage.contentHash(source),
                                List.of(
                                        new Observation(
                                                "O1",
                                                new Endpoint("보라", "보라"),
                                                new Endpoint("아라", "아라"),
                                                "함께 여행한다",
                                                List.of(
                                                        new Evidence(
                                                                0,
                                                                source.codePointCount(
                                                                        0, source.length()),
                                                                source)))),
                                0));
        apply(retry, b, action(b, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        Graph after = service.getRelationshipGraph(owner, workId, 1);
        assertThat(after.edges().getFirst().id()).isEqualTo(before.edges().getFirst().id());
        assertThat(after.nodes()).containsExactlyInAnyOrderElementsOf(before.nodes());
    }

    @Test
    void sourceReplacementWithIdenticalTextStillInvalidatesGraph() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        tx.executeWithoutResult(
                status -> {
                    Episode episode = jobs.findById(first).orElseThrow().getEpisode();
                    episode.updateContentStorage(
                            episode.getContentS3Key(), "v2", episode.getContentHash());
                });
        assertThat(service.getRelationshipGraph(owner, workId, null).availableEpisodes()).isEmpty();
        assertThat(service.getRelationshipGraph(owner, workId, null).analysisStatus())
                .isEqualTo("INVALIDATED");
    }

    @Test
    void purgeErasesFrozenAndCopiedEvidence() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        tx.executeWithoutResult(
                status ->
                        purge.purgeNarrativeTimeEvidence(
                                jobs.findById(first).orElseThrow().getEpisode().getId()));
        assertThat(analyses.findById(first).orElseThrow().getContext()).isNull();
        assertThat(service.getRelationshipGraph(owner, workId, null).edges()).isEmpty();
    }

    private Decision hold() {
        return new Decision(List.of("O1"), Operation.HOLD, null, null, null, null, null, null, null,
                null, TemporalScope.UNKNOWN, "관계를 확정할 근거가 부족하다");
    }

    private void succeed(UUID id) {
        tx.executeWithoutResult(t -> jobs.findById(id).orElseThrow().succeed(null, 0, 0));
    }

    @Test
    @DisplayName("명시적으로 종료한 관계 보류는 원래 판단과 근거를 보존하고 이후 문맥과 부분 표시에서 제외한다")
    void closedHoldPreservesEvidenceAndLeavesFutureContext() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, hold());
        succeed(first);
        var original = analyses.findById(first).orElseThrow();
        var originalContext = original.getContext().deepCopy();
        var originalDecisions = original.getDecisions().deepCopy();
        var originalHash = original.getCompletionHash();
        assertThat(service.getRelationshipGraph(owner, workId, null).partial()).isTrue();
        assertThat(holdGate.snapshot(workId, 1).count()).isZero();
        var snapshot = holdGate.snapshot(workId, 2);
        assertThat(snapshot.count()).isEqualTo(1);
        UUID requestId = UUID.randomUUID();
        tx.executeWithoutResult(t -> holdGate.closeHolds(snapshot, requestId));
        tx.executeWithoutResult(t -> holdGate.closeHolds(snapshot, requestId));
        assertThat(holdGate.snapshot(workId, 2).count()).isZero();
        assertThat(holdClosures.findClosedKeysByWorkId(workId)).containsExactly(first + ":O1");
        var retained = analyses.findById(first).orElseThrow();
        assertThat(retained.getContext()).isEqualTo(originalContext);
        assertThat(retained.getDecisions()).isEqualTo(originalDecisions);
        assertThat(retained.getCompletionHash()).isEqualTo(originalHash);
        assertThat(retained.getStatus()).isEqualTo(AnalysisStatus.APPLIED);
        assertThat(service.getRelationshipGraph(owner, workId, null).partial()).isFalse();
        UUID second = job(2);
        Context later = prepare(second);
        assertThat(later.heldObservations()).isEmpty();
        apply(second, later, hold());
        succeed(second);
        assertThat(holdGate.snapshot(workId, 3).entries()).singleElement()
                .extracting(RelationshipHoldReviewGate.Entry::analysisId).isEqualTo(second);
    }

    @Test
    @DisplayName("보류 종료는 이미 동결한 후행 문맥을 다시 쓰지 않는다")
    void closureDoesNotRewriteFrozenHeldContext() {
        UUID first = job(1);
        apply(first, prepare(first), hold());
        succeed(first);
        UUID second = job(2);
        Context frozen = prepare(second);
        assertThat(frozen.heldObservations()).hasSize(1);
        var snapshot = holdGate.snapshot(workId, 2);
        tx.executeWithoutResult(t -> holdGate.closeHolds(snapshot, UUID.randomUUID()));
        assertThat(service.getRelationshipAnalysis(second, lease).orElseThrow().heldObservations())
                .isEqualTo(frozen.heldObservations());
        assertThat(analyses.findById(second).orElseThrow().getContext().path("heldObservations")).hasSize(1);
    }

    @Test
    @DisplayName("진행 중 또는 실패한 원 작업과 오래된 출처의 보류는 종료 후보로 세지 않는다")
    void holdGateRequiresSuccessfulCurrentSource() {
        UUID first = job(1);
        apply(first, prepare(first), hold());
        assertThat(holdGate.snapshot(workId, 2).count()).isZero();
        tx.executeWithoutResult(t -> jobs.findById(first).orElseThrow().fail("synthetic failure"));
        assertThat(holdGate.snapshot(workId, 2).count()).isZero();
        succeed(first);
        assertThat(holdGate.snapshot(workId, 2).count()).isEqualTo(1);
        tx.executeWithoutResult(t -> em.createQuery("update AnalysisJob j set j.sourceReferenceInvalidatedAt = :now where j.id = :id")
                .setParameter("now", java.time.LocalDateTime.now()).setParameter("id", first).executeUpdate());
        assertThat(holdGate.snapshot(workId, 2).count()).isZero();
    }

    @Test
    @DisplayName("같은 회차의 새 관계 분석이 있으면 대체된 과거 보류를 종료 대상으로 다시 읽지 않는다")
    void supersededSameEpisodeHoldsDoNotReappearInGate() {
        UUID first = job(1);
        apply(first, prepare(first), hold());
        succeed(first);
        assertThat(holdGate.snapshot(workId, 2).count()).isEqualTo(1);
        UUID replacement = job(1);
        assertThat(holdGate.snapshot(workId, 2).count()).isZero();
        assertThat(analyses.findById(first).orElseThrow().getDecisions()).isNotNull();
        assertThat(analyses.findById(replacement).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    @Test
    @DisplayName("정상 보류를 종료해도 기술적 비교 실패와 불완전 회차 표시는 보존한다")
    void closingNormalHoldsDoesNotHideTechnicalFailures() {
        UUID first = job(1);
        apply(first, prepare(first), hold());
        succeed(first);
        UUID second = job(2);
        tx.executeWithoutResult(t -> jobs.findById(second).orElseThrow().enableRelationshipPartialResults());
        Context context = prepare(second);
        service.completeRelationshipAnalysis(second, lease, new Complete(1, context.contextToken(), List.of(),
                List.of(new org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.GroupFailure(
                        List.of("O1"), "RELATIONSHIP_ENDPOINTS_UNGROUNDED"))));
        tx.executeWithoutResult(t -> service.finalizeRelationshipAnalysis(jobs.findById(second).orElseThrow()));
        succeed(second);
        var failures = analyses.findById(second).orElseThrow().getFailures().deepCopy();
        var snapshot = holdGate.snapshot(workId, 3);
        assertThat(snapshot.entries()).singleElement().extracting(RelationshipHoldReviewGate.Entry::analysisId).isEqualTo(first);
        tx.executeWithoutResult(t -> holdGate.closeHolds(snapshot, UUID.randomUUID()));
        assertThat(analyses.findById(second).orElseThrow().getFailures()).isEqualTo(failures);
        Graph graph = service.getRelationshipGraph(owner, workId, null);
        assertThat(graph.partial()).isTrue();
        assertThat(graph.updateIncomplete()).isTrue();
        assertThat(graph.incompleteEpisodeNos()).containsExactly(2);
    }

    @Test
    @DisplayName("앞 회차의 상태 무효화는 뒤 회차 보류 경고를 숨기지 않지만 실제 원문 무효화 경계는 유지한다")
    void ordinaryInvalidationDoesNotHideLaterHoldButUnavailableSourceDoes() {
        UUID first = job(1);
        apply(first, prepare(first), hold());
        succeed(first);
        UUID second = job(2);
        apply(second, prepare(second), hold());
        succeed(second);
        assertThat(holdGate.snapshot(workId, 3).count()).isEqualTo(2);

        tx.executeWithoutResult(t -> analyses.findById(first).orElseThrow().invalidate());
        assertThat(holdGate.snapshot(workId, 3).entries()).singleElement()
                .extracting(RelationshipHoldReviewGate.Entry::analysisId).isEqualTo(second);

        tx.executeWithoutResult(t -> em.createQuery("update AnalysisJob j set j.sourceReferenceInvalidatedAt = :now where j.id = :id")
                .setParameter("now", java.time.LocalDateTime.now()).setParameter("id", first).executeUpdate());
        assertThat(holdGate.snapshot(workId, 3).count()).isZero();
    }

    @Test
    @DisplayName("별도 백필의 미공개 세대 보류는 공개 전 종료 대상으로 노출하지 않는다")
    void unpublishedBackfillHoldsWaitForPublishedGeneration() {
        UUID first = job(1);
        apply(first, prepare(first), hold());
        succeed(first);
        tx.executeWithoutResult(t -> {
            jobs.findById(first).orElseThrow().configureRelationship(1, true);
            em.createQuery("update RelationshipGraphAnalysis a set a.generation = 1 where a.id = :id")
                    .setParameter("id", first).executeUpdate();
            em.persist(org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillWork
                    .create(workId, "relationship-backfill-20261030-v1"));
        });
        assertThat(holdGate.snapshot(workId, 2).count()).isZero();
        tx.executeWithoutResult(t -> em.find(
                org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillWork.class, workId).publish());
        assertThat(holdGate.snapshot(workId, 2).entries()).singleElement()
                .extracting(RelationshipHoldReviewGate.Entry::analysisId).isEqualTo(first);
    }


}
