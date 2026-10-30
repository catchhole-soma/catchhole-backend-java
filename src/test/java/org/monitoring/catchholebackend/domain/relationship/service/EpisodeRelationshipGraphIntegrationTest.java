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
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
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

import java.util.List;
import java.util.UUID;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
class EpisodeRelationshipGraphIntegrationTest {
    @Autowired RelationshipGraphService service;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired AnalysisJobRepository jobs;
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
}
