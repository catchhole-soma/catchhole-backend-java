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
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphAnalysisRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkGenre;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.hash.Sha256Hasher;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@SpringBootTest(properties = "spring.config.import=")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("회차별 관계 확정과 재시도는 원문·사용자 정정 경계를 보존한다")
class EpisodeRelationshipGraphIntegrationTest {
    @Autowired RelationshipGraphService service;
    @Autowired RelationshipGraphCleanupService cleanup;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
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
    @Autowired Sha256Hasher hasher;
    @MockitoSpyBean AnalysisRunStateService runState;
    private Long owner, other;
    private UUID workId;
    private final UUID lease = UUID.randomUUID();
    private final String source = "아라와 보라는 함께 길을 떠났다.";
    // Per-episode synthetic manuscripts keyed by S3 key; other episodes read the shared source.
    private final java.util.Map<String, String> texts = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        when(storage.contentHash(anyString()))
                .thenAnswer(invocation -> hasher.hash(invocation.getArgument(0)));
        when(storage.getText(anyString()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return texts.getOrDefault(invocation.<String>getArgument(0), source);
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
        return jobWithSource(no, source, ordered);
    }

    /** The first job of an episode fixes its manuscript; later jobs of that episode reuse it. */
    private UUID jobWithSource(int no, String text, boolean ordered) {
        return tx.execute(
                status -> {
                    Work work = em.find(Work.class, workId);
                    Episode episode = episode(no, text);
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

    /** Must run inside a transaction. */
    private Episode episode(int no, String text) {
        Work work = em.find(Work.class, workId);
        return em.createQuery(
                        "select e from Episode e where e.work.id=:work and e.episodeNo=:no",
                        Episode.class)
                .setParameter("work", workId)
                .setParameter("no", no)
                .getResultStream()
                .findFirst()
                .orElseGet(
                        () -> {
                            String key = "source/" + UUID.randomUUID();
                            texts.put(key, text);
                            Episode e =
                                    Episode.create(
                                            work,
                                            null,
                                            no,
                                            no + "화",
                                            key,
                                            "v1",
                                            storage.contentHash(text),
                                            text.length());
                            em.persist(e);
                            return e;
                        });
    }

    private Observation observation(String ref, String text, String sourceName, String targetName) {
        return new Observation(
                ref,
                new Endpoint(sourceName, sourceName),
                new Endpoint(targetName, targetName),
                "함께 여행한다",
                List.of(new Evidence(0, text.codePointCount(0, text.length()), text)));
    }

    private Context prepareWith(UUID id, String text, Observation... observations) {
        return service.prepareRelationshipAnalysis(
                id, lease, new Prepare(1, storage.contentHash(text), List.of(observations), 0));
    }

    private Decision add(Context context, String ref, Kind kind, String summary) {
        var observation =
                context.observations().stream().filter(o -> o.ref().equals(ref)).findFirst().orElseThrow();
        return new Decision(
                List.of(ref),
                Operation.ADD,
                null,
                observation.sourceNodeRefs().getFirst(),
                observation.targetNodeRefs().getFirst(),
                Axis.SOCIAL,
                kind,
                "동행",
                summary,
                Direction.MUTUAL,
                TemporalScope.PRESENT,
                null);
    }

    /** A confirmed character discovery is where an alias is recorded for its profile. */
    private void confirmDiscovery(UUID jobId, UUID characterId, String name) {
        tx.executeWithoutResult(
                status -> {
                    AnalysisJob job = jobs.findById(jobId).orElseThrow();
                    persistConfirmedDiscovery(job, characterId, name);
                });
    }

    /** Confirms an alias on an episode through an ordinary job that has no relationship graph. */
    private void confirmedAliasAt(int no, String text, UUID characterId, String name) {
        tx.executeWithoutResult(
                status -> {
                    Episode episode = episode(no, text);
                    AnalysisJob job =
                            AnalysisJob.create(
                                    episode.getWork(), null, episode, AnalysisJobType.SETTING_EXTRACTION);
                    em.persist(job);
                    persistConfirmedDiscovery(job, characterId, name);
                });
    }

    private void persistConfirmedDiscovery(AnalysisJob job, UUID characterId, String name) {
        ObjectMapper json = new ObjectMapper();
        SettingCandidate candidate =
                SettingCandidate.createCharacterDiscovery(
                        job.getWork(),
                        job.getEpisode(),
                        UUID.randomUUID(),
                        job,
                        name,
                        name,
                        characterId,
                        SettingCandidateMatchStatus.MATCHED,
                        json.createArrayNode(),
                        BigDecimal.ONE,
                        json.createObjectNode());
        candidate.confirm();
        em.persist(candidate);
    }

    private long nodeCount() {
        return tx.execute(
                t ->
                        em.createQuery(
                                        "select count(n) from RelationshipGraphNode n where n.workId=:work",
                                        Long.class)
                                .setParameter("work", workId)
                                .getSingleResult());
    }

    private org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.ContextNode
            contextNode(Context context, String ref) {
        return context.nodes().stream().filter(n -> n.ref().equals(ref)).findFirst().orElseThrow();
    }

    /** Seeds 1화 with the full name linked to a profile and returns that profile node. */
    private UUID profileNodeAt(int no, String text, String name, String partner, UUID characterId) {
        UUID id = jobWithSource(no, text, false);
        Context context = prepareWith(id, text, observation("O1", text, name, partner));
        confirmDiscovery(id, characterId, name);
        apply(id, context, add(context, "O1", Kind.COMPANIONSHIP, "함께 여행한다"));
        return boundNodeId(id, context.observations().getFirst().sourceNodeRefs().getFirst());
    }

    private long identityQueryExecutions(org.hibernate.stat.Statistics statistics) {
        return java.util.Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains("SettingCandidate") && query.contains("as characterId"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
    }

    @ParameterizedTest(name = "tampered={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("#387 자동 누적 회차의 관계 문맥 준비는 lease가 생략한 고정 입력 hash를 직접 확인해 변조된 입력의 이름으로 노드를 묶지 않는다")
    void automaticOrderedPrepareChecksFrozenInputHash(boolean tampered) {
        var journal = new org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal();
        UUID id = tx.execute(status -> {
            AnalysisJob job = AnalysisJob.create(em.find(Work.class, workId), null, episode(1, source),
                    AnalysisJobType.SETTING_EXTRACTION);
            job.enableRelationshipAnalysis();
            job.configureReviewMode(org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC);
            job.initializeOrderedRun(UUID.randomUUID(), 1, 0, null,
                    new ObjectMapper().createObjectNode().put("automaticInputCapturedAtClaim", true));
            ObjectNode input = journal.emptyState();
            job.captureAutomaticInput(input);
            job.prepareOrderedInput(journal.hash(input));
            em.persist(job);
            em.flush();
            service.beginRelationshipAnalysis(job);
            return job.getId();
        });
        if (tampered) {
            tx.executeWithoutResult(status -> {
                AnalysisJob job = jobs.findById(id).orElseThrow();
                ObjectNode changed = (ObjectNode) job.getAutomaticInputState().deepCopy();
                changed.put("tamperedForTest", true);
                ReflectionTestUtils.setField(job, "automaticInputState", changed);
            });
        }
        clearInvocations(runState);

        if (!tampered) {
            assertThat(prepare(id).observations()).hasSize(1);
            verify(runState).assertValidInput(argThat(job -> job.getId().equals(id)));
            return;
        }
        assertThatThrownBy(() -> prepare(id)).isInstanceOfSatisfying(AppException.class, error ->
                assertThat(error.getResultCode().getCode()).isEqualTo("ANALYSIS_RUN_STATE_CONFLICT"));
        assertThat(tx.<AnalysisStatus>execute(status -> analyses.findById(id).orElseThrow().getStatus()))
                .isEqualTo(AnalysisStatus.PENDING);
        assertThat(nodeCount()).isZero();
    }

    @Test
    @DisplayName("그 회차까지 확정된 별칭은 같은 프로필의 기존 노드를 다시 쓰고 저장된 이름을 문맥에 보인다")
    void aliasConfirmedBeforeCutoffReusesProfileNode() {
        UUID characterId = character("아라 린");
        UUID profileNode =
                profileNodeAt(1, "아라 린과 보라는 함께 길을 떠났다.", "아라 린", "보라", characterId);
        confirmedAliasAt(2, "아라는 웃었다.", characterId, "아라");
        long before = nodeCount();

        String text = "아라와 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "아라", "보라"));

        String ref = context.observations().getFirst().sourceNodeRefs().getFirst();
        assertThat(boundNodeId(third, ref)).isEqualTo(profileNode);
        assertThat(nodeCount()).isEqualTo(before);
        assertThat(contextNode(context, ref).name()).isEqualTo("아라 린");
        assertThat(contextNode(context, ref).profileRegistered()).isTrue();
        assertThat(context.relationships()).singleElement()
                .satisfies(r -> assertThat(List.of(r.sourceNodeRef(), r.targetNodeRef())).contains(ref));
    }

    @ParameterizedTest(name = "ordered={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("미래 회차에 확정된 별칭과 순서 분석 입력의 별칭 문자열은 과거 회차 노드 연결에 쓰지 않는다")
    void aliasConfirmedInFutureEpisodeIsNotUsed(boolean ordered) {
        UUID characterId = character("아라 린");
        UUID profileNode =
                profileNodeAt(1, "아라 린과 보라는 함께 길을 떠났다.", "아라 린", "보라", characterId);
        // GH385: the alias shares no part with "아라 린", so only the future alias could link it.
        confirmedAliasAt(5, "늑대는 웃었다.", characterId, "늑대");
        if (ordered) {
            // S0 aliases have no per-alias episode; only the representative name is authoritative.
            var input = new ObjectMapper().createObjectNode();
            var character = input.putArray("characters").addObject()
                    .put("name", "아라 린")
                    .put("actualCharacterId", characterId.toString());
            character.putArray("aliases").add("늑대");
            doReturn(input).when(runState).getInputState(any());
        }

        String text = "늑대와 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, ordered);
        Context context = prepareWith(third, text, observation("O1", text, "늑대", "보라"));

        String ref = context.observations().getFirst().sourceNodeRefs().getFirst();
        assertThat(boundNodeId(third, ref)).isNotEqualTo(profileNode);
        assertThat(contextNode(context, ref).name()).isEqualTo("늑대");
        assertThat(contextNode(context, ref).profileRegistered()).isFalse();
        assertThat(nodeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 별칭이 두 프로필에 확정되어 있으면 노드를 만들거나 연결하지 않고 보류한다")
    void aliasMappedToTwoProfilesHolds() {
        UUID first = character("아라 린"), second = character("아라 벨");
        confirmedAliasAt(1, "아라는 웃었다.", first, "아라");
        confirmedAliasAt(2, "아라는 울었다.", second, "아라");

        String text = "아라와 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "아라", "보라"));

        assertThat(context.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(context.nodes()).extracting(n -> n.name()).containsExactly("보라");
        assertThat(nodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("뒤 회차에만 있는 프로필 노드를 별칭으로 다시 써도 그 노드의 미래 이름을 과거 문맥에 노출하지 않는다")
    void aliasReusesFutureProfileNodeWithoutLeakingItsName() {
        UUID characterId = character("아라 린");
        UUID profileNode =
                profileNodeAt(5, "아라 린과 다라는 함께 길을 떠났다.", "아라 린", "다라", characterId);
        confirmedAliasAt(2, "아라는 웃었다.", characterId, "아라");

        String text = "아라와 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "아라", "보라"));

        String ref = context.observations().getFirst().sourceNodeRefs().getFirst();
        assertThat(boundNodeId(third, ref)).isEqualTo(profileNode);
        assertThat(contextNode(context, ref).name()).isEqualTo("아라");
        assertThat(contextNode(context, ref).profileRegistered()).isFalse();
        assertThat(context.nodes()).noneMatch(n -> n.name().equals("아라 린"));
        tx.executeWithoutResult(t -> assertThat(
                em.find(RelationshipGraphNode.class, profileNode).getFirstEpisodeNo()).isEqualTo(5));
        // The alias is kept as a redirect row so the profile node stays visible from 3화.
        RelationshipGraphNode redirect = tx.execute(t -> em.createQuery(
                        "select n from RelationshipGraphNode n where n.workId=:work and n.mergedIntoNodeId=:profile",
                        RelationshipGraphNode.class)
                .setParameter("work", workId).setParameter("profile", profileNode).getSingleResult());
        assertThat(redirect.getName()).isEqualTo("아라");
        assertThat(redirect.getFirstEpisodeNo()).isEqualTo(3);
        assertThat(redirect.getMergedAtEpisodeNo()).isEqualTo(3);
        assertThat(redirect.getCharacterId()).isNull();
        apply(third, context, add(context, "O1", Kind.COMPANIONSHIP, "3화 동행"));

        // 4화 mentions only the partner. Its legacy neighbor context still includes the profile
        // node's 3화 relationship, under the alias known by then, not the 5화 full name.
        String later = "보라와 다라는 함께 길을 떠났다.";
        UUID fourth = jobWithSource(4, later, false);
        Context next = prepareWith(fourth, later, observation("O1", later, "보라", "다라"));
        assertThat(next.relationships()).singleElement().satisfies(r -> {
            String profileRef = boundNodeId(fourth, r.sourceNodeRef()).equals(profileNode)
                    ? r.sourceNodeRef() : r.targetNodeRef();
            assertThat(boundNodeId(fourth, profileRef)).isEqualTo(profileNode);
            assertThat(contextNode(next, profileRef).name()).isEqualTo("아라");
        });
        assertThat(next.nodes()).noneMatch(n -> n.name().equals("아라 린"));
        assertThat(service.getRelationshipGraph(owner, workId, 3).nodes())
                .filteredOn(n -> n.id().equals(profileNode)).singleElement()
                .satisfies(n -> assertThat(n.profileRegistered()).isFalse());
    }

    private void assertMergedInto(UUID alias, UUID profile, int mergedAt, int aliasFirst) {
        assertMergedInto(alias, profile, mergedAt, aliasFirst, "아라");
    }

    private void assertMergedInto(UUID alias, UUID profile, int mergedAt, int aliasFirst, String aliasName) {
        tx.executeWithoutResult(t -> {
            em.clear();
            RelationshipGraphNode node = em.find(RelationshipGraphNode.class, alias);
            assertThat(node.getMergedIntoNodeId()).isEqualTo(profile);
            assertThat(node.getMergedAtEpisodeNo()).isEqualTo(mergedAt);
            assertThat(node.getCharacterId()).isNull();
            assertThat(node.getName()).isEqualTo(aliasName);
            assertThat(node.getFirstEpisodeNo()).isEqualTo(aliasFirst);
        });
    }

    private static java.util.Set<UUID> endpoints(Edge edge) {
        return java.util.Set.of(edge.sourceNodeId(), edge.targetNodeId());
    }

    @Test
    @DisplayName("반영 때 같은 프로필로 확정된 별칭 노드는 프로필 노드로 합치고 모든 회차 조회에서 한 노드로 해소한다")
    void aliasConfirmedAtFinalizeMergesExistingNode() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID alias = boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID bora = boundNodeId(first, initial.observations().getFirst().targetNodeRefs().getFirst());
        UUID characterId = character("아라 린 얀델");
        UUID profile = profileNodeAt(2, "아라 린과 다라는 함께 길을 떠났다.", "아라 린", "다라", characterId);

        UUID third = job(3);
        Context context = prepare(third);
        confirmDiscovery(third, characterId, "아라");
        apply(third, context, action(context, Operation.UPDATE, Kind.COMPANIONSHIP, "다시 함께 여행한다"));

        assertMergedInto(alias, profile, 3, 1);
        // The profile node's own first episode and link episode stay as recorded.
        assertProfileLink(profile, characterId, 2, 2);
        Graph latest = service.getRelationshipGraph(owner, workId, 3);
        assertThat(latest.nodes()).extracting(n -> n.id()).doesNotContain(alias).contains(profile, bora);
        assertThat(latest.nodes()).filteredOn(n -> n.id().equals(profile)).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("아라 린 얀델");
            assertThat(n.profileRegistered()).isTrue();
        });
        assertThat(latest.edges()).allSatisfy(e -> assertThat(endpoints(e)).doesNotContain(alias));
        assertThat(latest.edges()).filteredOn(e -> endpoints(e).equals(java.util.Set.of(profile, bora)))
                .singleElement().extracting(Edge::summary).isEqualTo("다시 함께 여행한다");
        Graph past = service.getRelationshipGraph(owner, workId, 1);
        assertThat(past.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(profile, bora);
        assertThat(past.edges()).singleElement()
                .satisfies(e -> assertThat(endpoints(e)).containsExactlyInAnyOrder(profile, bora));
        assertThat(past.nodes()).filteredOn(n -> n.id().equals(profile)).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("아라");
            assertThat(n.profileRegistered()).isFalse();
        });

        // The next context resolves the alias name to the profile node; no second node is made.
        long before = nodeCount();
        UUID fourth = job(4);
        Context next = prepare(fourth);
        assertThat(boundNodeId(fourth, next.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(profile);
        assertThat(nodeCount()).isEqualTo(before);

        // V118's self-reference is NO ACTION, so the work purge's single node DELETE still passes.
        // Same statements and order as WorkPurgeDataRepository (H2 lacks its non-entity tables).
        tx.executeWithoutResult(t -> {
            for (String table : List.of(
                    "relationship_graph_events", "relationship_graph_edges", "relationship_graph_nodes"))
                em.createNativeQuery("delete from " + table + " where work_id = ?1")
                        .setParameter(1, workId).executeUpdate();
        });
        assertThat(nodeCount()).isZero();
    }

    @Test
    @DisplayName("합친 노드의 관계도 사용자 정정·삭제를 유지하고 재분석의 UPDATE·ADD가 되살리지 않는다")
    void mergedNodeKeepsUserCorrectionAndSuppression() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(
                first,
                initial,
                action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"),
                action(initial, Operation.ADD, Kind.FRIENDSHIP, "친구다"));
        UUID alias = boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID bora = boundNodeId(first, initial.observations().getFirst().targetNodeRefs().getFirst());
        Graph original = service.getRelationshipGraph(owner, workId, 1);
        Edge companion = original.edges().stream().filter(e -> e.kind() == Kind.COMPANIONSHIP).findFirst().orElseThrow();
        Edge friend = original.edges().stream().filter(e -> e.kind() == Kind.FRIENDSHIP).findFirst().orElseThrow();
        service.correctRelationshipEdge(
                owner, workId, companion.id(), new Correction(companion.version(), "사용자가 정정한 동행"));
        service.deleteRelationshipEdge(owner, workId, friend.id(), friend.version());
        long correctedVersion = service.getRelationshipGraph(owner, workId, 1).edges().getFirst().version();
        UUID characterId = character("아라 린");
        UUID profile = profileNodeAt(2, "아라 린과 다라는 함께 길을 떠났다.", "아라 린", "다라", characterId);

        UUID third = job(3);
        Context held = prepare(third);
        confirmDiscovery(third, characterId, "아라");
        apply(third, held, hold());
        assertMergedInto(alias, profile, 3, 1);
        assertThat(service.getRelationshipGraph(owner, workId, 3).edges())
                .filteredOn(e -> endpoints(e).equals(java.util.Set.of(profile, bora)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.id()).isEqualTo(companion.id());
                    assertThat(e.summary()).isEqualTo("사용자가 정정한 동행");
                    // Redirecting the node is not an edge change.
                    assertThat(e.version()).isEqualTo(correctedVersion);
                });

        UUID fourth = job(4);
        Context again = prepare(fourth);
        assertThat(boundNodeId(fourth, again.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(profile);
        assertThat(again.relationships()).noneMatch(r -> r.kind() == Kind.FRIENDSHIP);
        apply(
                fourth,
                again,
                action(again, Operation.UPDATE, Kind.COMPANIONSHIP, "자동 분석 설명"),
                action(again, Operation.ADD, Kind.FRIENDSHIP, "다시 친구다"));
        Graph after = service.getRelationshipGraph(owner, workId, 4);
        assertThat(after.edges()).filteredOn(e -> endpoints(e).equals(java.util.Set.of(profile, bora)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.id()).isEqualTo(companion.id());
                    assertThat(e.summary()).isEqualTo("사용자가 정정한 동행");
                });
        assertThat(after.edges()).noneMatch(e -> e.kind() == Kind.FRIENDSHIP);
        Long friendshipEdges = tx.execute(t -> em.createQuery(
                        "select count(e) from RelationshipGraphEdge e where e.workId=:work and e.kind=:kind", Long.class)
                .setParameter("work", workId).setParameter("kind", Kind.FRIENDSHIP).getSingleResult());
        assertThat(friendshipEdges).isEqualTo(1L);
        // The update event recorded on the alias-node edge keeps both endpoint names oriented.
        assertThat(relationshipEvents()).filteredOn(e -> e.getEpisodeNo() == 4).singleElement()
                .satisfies(e -> assertThat(java.util.Set.of(e.getSourceName(), e.getTargetName()))
                        .containsExactlyInAnyOrder("아라 린", "보라"));
    }

    @Test
    @DisplayName("합친 노드와 프로필 노드 사이의 옛 관계는 숨기고 별칭 노드의 실패 쌍은 프로필 노드 쌍으로 계속 막는다")
    void mergedNodeHidesSelfLoopAndResolvesBlockedPairs() {
        String text = "아라와 아라 린과 보라는 함께 길을 떠났다.";
        UUID characterId = character("아라 린");
        UUID first = jobWithSource(1, text, false);
        tx.executeWithoutResult(t -> jobs.findById(first).orElseThrow().enableRelationshipPartialResults());
        Context c1 = prepareWith(first, text,
                observation("O1", text, "아라", "아라 린"), observation("O2", text, "아라", "보라"));
        confirmDiscovery(first, characterId, "아라 린");
        service.completeRelationshipAnalysis(first, lease, new Complete(1, c1.contextToken(),
                List.of(add(c1, "O1", Kind.COMPANIONSHIP, "함께 여행한다")),
                List.of(new org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.GroupFailure(
                        List.of("O2"), "RELATIONSHIP_ENDPOINTS_UNGROUNDED"))));
        tx.executeWithoutResult(t -> service.finalizeRelationshipAnalysis(jobs.findById(first).orElseThrow()));
        UUID alias = boundNodeId(first, c1.observations().getFirst().sourceNodeRefs().getFirst());
        UUID profile = boundNodeId(first, c1.observations().getFirst().targetNodeRefs().getFirst());
        UUID selfLoop = service.getRelationshipGraph(owner, workId, 1).edges().getFirst().id();

        String text2 = "아라와 보라는 다시 만났다.";
        UUID second = jobWithSource(2, text2, false);
        tx.executeWithoutResult(t -> jobs.findById(second).orElseThrow().enableRelationshipPartialResults());
        Context c2 = prepareWith(second, text2, observation("O1", text2, "아라", "보라"));
        assertThat(c2.blockedPairs()).singleElement().extracting(b -> b.firstFailedEpisodeNo()).isEqualTo(1);
        confirmDiscovery(second, characterId, "아라");
        service.completeRelationshipAnalysis(second, lease, new Complete(1, c2.contextToken(), List.of(),
                List.of(new org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.GroupFailure(
                        List.of("O1"), "RELATIONSHIP_PREDECESSOR_INCOMPLETE"))));
        tx.executeWithoutResult(t -> service.finalizeRelationshipAnalysis(jobs.findById(second).orElseThrow()));
        assertMergedInto(alias, profile, 2, 1);

        for (int no : List.of(1, 2)) {
            Graph graph = service.getRelationshipGraph(owner, workId, no);
            assertThat(graph.edges()).isEmpty();
            assertThat(graph.totalEdgeCount()).isZero();
            assertThatThrownBy(() -> service.getRelationshipEdge(owner, workId, selfLoop, no))
                    .isInstanceOfSatisfying(AppException.class, failure -> assertThat(failure.getResultCode())
                            .isEqualTo(org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_NOT_FOUND));
        }

        UUID third = jobWithSource(3, text2, false);
        tx.executeWithoutResult(t -> jobs.findById(third).orElseThrow().enableRelationshipPartialResults());
        Context c3 = prepareWith(third, text2, observation("O1", text2, "아라", "보라"));
        String sourceRef = c3.observations().getFirst().sourceNodeRefs().getFirst();
        String targetRef = c3.observations().getFirst().targetNodeRefs().getFirst();
        assertThat(boundNodeId(third, sourceRef)).isEqualTo(profile);
        assertThat(c3.blockedPairs()).singleElement().satisfies(blocked -> {
            assertThat(blocked.firstFailedEpisodeNo()).isEqualTo(1);
            assertThat(java.util.Set.of(blocked.sourceNodeRef(), blocked.targetNodeRef()))
                    .containsExactlyInAnyOrder(sourceRef, targetRef);
        });
        assertThat(c3.relationships()).isEmpty();
    }

    @Test
    @DisplayName("병합 전에 옛 노드로 동결한 문맥도 병합 뒤 충돌 없이 반영하고 조회는 프로필 노드로 보인다")
    void staleFrozenBindingFinalizesAfterMerge() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID alias = boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID bora = boundNodeId(first, initial.observations().getFirst().targetNodeRefs().getFirst());
        UUID characterId = character("아라 린");
        UUID profile = profileNodeAt(2, "아라 린과 다라는 함께 길을 떠났다.", "아라 린", "다라", characterId);
        UUID third = job(3);
        Context frozen = prepare(third);
        assertThat(boundNodeId(third, frozen.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(alias);
        // Frozen before any redirect: no alias dependency field, so its hashes stay as captured.
        assertThat(analyses.findById(third).orElseThrow().getBindings().has("aliasDependencyVersion")).isFalse();

        UUID fourth = job(4);
        Context later = prepare(fourth);
        confirmDiscovery(fourth, characterId, "아라");
        apply(fourth, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "4화 동행"));
        assertMergedInto(alias, profile, 4, 1);

        apply(third, frozen, action(frozen, Operation.UPDATE, Kind.COMPANIONSHIP, "3화 동행"));
        assertThat(analyses.findById(third).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.APPLIED);
        assertMergedInto(alias, profile, 4, 1);
        for (int no : List.of(3, 4)) {
            Graph graph = service.getRelationshipGraph(owner, workId, no);
            assertThat(graph.nodes()).extracting(n -> n.id()).doesNotContain(alias);
            assertThat(graph.edges()).filteredOn(e -> endpoints(e).equals(java.util.Set.of(profile, bora)))
                    .singleElement().extracting(Edge::summary).isEqualTo(no + "화 동행");
        }
    }

    @Test
    @DisplayName("같은 반영에서 프로필로 합쳐질 별칭 노드의 ADD도 프로필 노드에서 삭제한 관계를 되살리지 않는다")
    void addOnAliasMergedInSameFinalizeRespectsProfileDeletion() {
        UUID characterId = character("아라 린");
        UUID profile = profileNodeAt(1, "아라 린과 보라는 함께 길을 떠났다.", "아라 린", "보라", characterId);
        Edge deleted = service.getRelationshipGraph(owner, workId, 1).edges().getFirst();
        service.deleteRelationshipEdge(owner, workId, deleted.id(), deleted.version());

        // 2화 prepare 때 "늑대"는 아직 별칭이 아니고 대표 이름과 겹치지도 않으므로 독립 노드다.
        // 확정은 이 Job 안에서 일어난다.
        String text = "늑대와 보라는 다시 만났다.";
        UUID second = jobWithSource(2, text, false);
        Context context = prepareWith(second, text, observation("O1", text, "늑대", "보라"));
        UUID alias = boundNodeId(second, context.observations().getFirst().sourceNodeRefs().getFirst());
        assertThat(alias).isNotEqualTo(profile);
        assertThat(context.relationships()).isEmpty();
        confirmDiscovery(second, characterId, "늑대");
        apply(second, context, add(context, "O1", Kind.COMPANIONSHIP, "다시 함께 여행한다"));

        assertMergedInto(alias, profile, 2, 2, "늑대");
        assertThat(service.getRelationshipGraph(owner, workId, 2).edges()).isEmpty();
        Long companionEdges = tx.execute(t -> em.createQuery(
                        "select count(e) from RelationshipGraphEdge e where e.workId=:work and e.kind=:kind", Long.class)
                .setParameter("work", workId).setParameter("kind", Kind.COMPANIONSHIP).getSingleResult());
        assertThat(companionEdges).isEqualTo(1L);
    }

    @ParameterizedTest(name = "partial={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("별칭으로 프로필 노드에 묶였던 보류 관측은 대표 이름만 나오는 회차에서도 같은 인물 쌍으로 다시 제공한다")
    void heldAliasObservationResolvesThroughItsFrozenProfileNode(boolean partial) {
        UUID characterId = character("아라 린");
        UUID profile = profileNodeAt(1, "아라 린과 보라는 함께 길을 떠났다.", "아라 린", "보라", characterId);
        confirmedAliasAt(2, "아라는 웃었다.", characterId, "아라");

        String aliasText = "아라와 보라는 다시 만났다.";
        UUID third = jobWithSource(3, aliasText, false);
        if (partial) tx.executeWithoutResult(t -> jobs.findById(third).orElseThrow().enableRelationshipPartialResults());
        Context held = prepareWith(third, aliasText, observation("O1", aliasText, "아라", "보라"));
        assertThat(boundNodeId(third, held.observations().getFirst().sourceNodeRefs().getFirst())).isEqualTo(profile);
        apply(third, held, hold());

        String fullText = "아라 린과 보라는 웃었다.";
        UUID fourth = jobWithSource(4, fullText, false);
        if (partial) tx.executeWithoutResult(t -> jobs.findById(fourth).orElseThrow().enableRelationshipPartialResults());
        Context next = prepareWith(fourth, fullText, observation("O1", fullText, "아라 린", "보라"));
        var current = next.observations().getFirst();
        assertThat(boundNodeId(fourth, current.sourceNodeRefs().getFirst())).isEqualTo(profile);
        assertThat(next.heldObservations()).singleElement().satisfies(h -> {
            assertThat(h.source().name()).isEqualTo("아라");
            assertThat(h.sourceNodeRefs()).isEqualTo(current.sourceNodeRefs());
            assertThat(h.targetNodeRefs()).isEqualTo(current.targetNodeRefs());
            assertThat(h.sourceEpisodeNo()).isEqualTo(3);
        });
        // The model can now resolve the held evidence together with the current observation.
        Decision add = add(next, "O1", Kind.COMPANIONSHIP, "다시 함께 여행한다");
        String relationship = next.relationships().getFirst().ref();
        var r = next.relationships().getFirst();
        apply(fourth, next, new Decision(List.of("O1", "H1"), Operation.UPDATE, relationship, r.sourceNodeRef(),
                r.targetNodeRef(), r.axis(), r.kind(), r.label(), add.summary(), r.direction(),
                TemporalScope.PRESENT, null));
        assertThat(service.getRelationshipGraph(owner, workId, 4).partial()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"correction", "deletion", "correction-partial", "deletion-partial"})
    @DisplayName("병합 뒤 준비한 문맥은 프로필 노드로 투영한 별칭 노드 관계의 사용자 정정·삭제도 충돌로 거부한다")
    void userChangeOnRedirectedEdgeAfterPrepareRejectsStaleComparison(String mutation) {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID alias = boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID bora = boundNodeId(first, initial.observations().getFirst().targetNodeRefs().getFirst());
        UUID characterId = character("아라 린");
        UUID profile = profileNodeAt(2, "아라 린과 다라는 함께 길을 떠났다.", "아라 린", "다라", characterId);
        UUID third = job(3);
        Context merging = prepare(third);
        confirmDiscovery(third, characterId, "아라");
        apply(third, merging, hold());
        assertMergedInto(alias, profile, 3, 1);

        UUID fourth = job(4);
        if (mutation.endsWith("partial"))
            tx.executeWithoutResult(t -> jobs.findById(fourth).orElseThrow().enableRelationshipPartialResults());
        Context frozen = prepare(fourth);
        assertThat(boundNodeId(fourth, frozen.observations().getFirst().sourceNodeRefs().getFirst())).isEqualTo(profile);
        // The alias-node edge is projected onto the profile node's ref.
        var observed = frozen.observations().getFirst();
        var pair = java.util.Set.of(observed.sourceNodeRefs().getFirst(), observed.targetNodeRefs().getFirst());
        var projected = frozen.relationships().stream()
                .filter(r -> java.util.Set.of(r.sourceNodeRef(), r.targetNodeRef()).equals(pair))
                .findFirst().orElseThrow();
        assertThat(UUID.fromString(analyses.findById(fourth).orElseThrow().getBindings()
                .path("relationships").path(projected.ref()).asText()))
                .isEqualTo(service.getRelationshipGraph(owner, workId, 1).edges().getFirst().id());
        var aliasPairs = analyses.findById(fourth).orElseThrow().getBindings().path("aliasDependencyPairs");
        assertThat(aliasPairs.isArray()).isTrue();
        assertThat(aliasPairs).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .contains(org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules
                        .pair(alias.toString(), bora.toString()));
        Decision update = new Decision(List.of("O1"), Operation.UPDATE, projected.ref(), projected.sourceNodeRef(),
                projected.targetNodeRef(), projected.axis(), projected.kind(), projected.label(), "새 설명",
                projected.direction(), TemporalScope.PRESENT, null);
        Edge edge = service.getRelationshipGraph(owner, workId, 3).edges().stream()
                .filter(e -> endpoints(e).equals(java.util.Set.of(profile, bora))).findFirst().orElseThrow();
        if (mutation.startsWith("correction"))
            service.correctRelationshipEdge(owner, workId, edge.id(), new Correction(edge.version(), "사용자가 바로잡은 설명"));
        else service.deleteRelationshipEdge(owner, workId, edge.id(), edge.version());

        java.util.function.Consumer<AppException> conflict = failure -> assertThat(failure.getResultCode())
                .isEqualTo(org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT);
        assertThatThrownBy(() -> service.getRelationshipAnalysis(fourth, lease))
                .isInstanceOfSatisfying(AppException.class, conflict);
        assertThatThrownBy(() -> service.completeRelationshipAnalysis(fourth, lease,
                new Complete(1, frozen.contextToken(), List.of(update))))
                .isInstanceOfSatisfying(AppException.class, conflict);
        assertThat(analyses.findById(fourth).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PREPARED);
    }

    private org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Node graphNode(
            int episodeNo, UUID nodeId) {
        return service.getRelationshipGraph(owner, workId, episodeNo).nodes().stream()
                .filter(n -> n.id().equals(nodeId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("등록 회차부터 노드 이름은 현재 프로필 이름을 따라가고 미등록·보관·삭제 프로필은 관측 이름으로 돌아간다")
    void registeredNodeDisplaysProfileNameAndFollowsRename() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID nodeId = boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID characterId = character("아라 린");
        UUID second = job(2);
        Context later = prepare(second);
        confirmDiscovery(second, characterId, "아라");
        apply(second, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "함께 여행한다"));
        assertProfileLink(nodeId, characterId, 1, 2);

        assertThat(graphNode(1, nodeId)).satisfies(n -> {
            assertThat(n.name()).isEqualTo("아라");
            assertThat(n.profileRegistered()).isFalse();
        });
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        Graph registered;
        try {
            registered = service.getRelationshipGraph(owner, workId, 2);
            assertThat(statistics.getEntityStatistics(WorkCharacter.class.getName()).getLoadCount()).isZero();
            assertThat(statistics.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isZero();
            assertThat(java.util.Arrays.stream(statistics.getQueries())
                    .filter(q -> q.contains("WorkCharacter") && q.contains("character.name as name"))
                    .mapToLong(q -> statistics.getQueryStatistics(q).getExecutionCount()).sum()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
        assertThat(registered.nodes()).filteredOn(n -> n.id().equals(nodeId)).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("아라 린");
            assertThat(n.profileRegistered()).isTrue();
        });
        var detail = service.getRelationshipEdge(owner, workId, registered.edges().getFirst().id(), 2);
        assertThat(List.of(detail.sourceNode(), detail.targetNode()))
                .filteredOn(n -> n.id().equals(nodeId)).singleElement()
                .satisfies(n -> assertThat(n.name()).isEqualTo("아라 린"));

        tx.executeWithoutResult(t -> em.find(WorkCharacter.class, characterId).updateBasicInfo("아라 린 2", null));
        Graph renamed = service.getRelationshipGraph(owner, workId, 2);
        assertThat(renamed.nodes()).filteredOn(n -> n.id().equals(nodeId)).singleElement()
                .satisfies(n -> assertThat(n.name()).isEqualTo("아라 린 2"));
        assertThat(renamed.revision()).isNotEqualTo(registered.revision());
        assertThat(graphNode(1, nodeId).name()).isEqualTo("아라");

        // An archived profile is no longer on the dashboard: fall back to the observed name.
        tx.executeWithoutResult(t -> em.find(WorkCharacter.class, characterId).archive());
        assertThat(graphNode(2, nodeId)).satisfies(n -> {
            assertThat(n.name()).isEqualTo("아라");
            assertThat(n.profileRegistered()).isTrue();
        });
        // Deleting a profile sets the node FK to null (ON DELETE SET NULL): unregistered again.
        tx.executeWithoutResult(t -> {
            em.find(WorkCharacter.class, characterId).restore();
            em.createNativeQuery("update relationship_graph_nodes set character_id = null where id = ?1")
                    .setParameter(1, nodeId).executeUpdate();
        });
        assertThat(graphNode(2, nodeId)).satisfies(n -> {
            assertThat(n.name()).isEqualTo("아라");
            assertThat(n.profileRegistered()).isFalse();
        });
        tx.executeWithoutResult(t -> assertThat(em.find(RelationshipGraphNode.class, nodeId).getName())
                .isEqualTo("아라"));
    }

    @Test
    @DisplayName("한 프로필에 등록된 노드가 둘 이상이면 같은 프로필 이름을 반복하지 않고 관측 이름을 보여 준다")
    void profileWithSeveralRegisteredNodesKeepsObservedNames() {
        UUID first = job(1);
        Context initial = prepare(first);
        apply(first, initial, action(initial, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID source = boundNodeId(first, initial.observations().getFirst().sourceNodeRefs().getFirst());
        UUID target = boundNodeId(first, initial.observations().getFirst().targetNodeRefs().getFirst());
        String sourceName = graphNode(1, source).name(), targetName = graphNode(1, target).name();
        UUID characterId = character("아라 린");
        // Already split production data (before the one-time cleanup): link nodes to one profile
        // directly, the source by episode 1 and the target from episode 2.
        tx.executeWithoutResult(t -> em.createNativeQuery(
                        "update relationship_graph_nodes set character_id = ?1, character_linked_episode_no = 1 where id = ?2")
                .setParameter(1, characterId).setParameter(2, source).executeUpdate());
        tx.executeWithoutResult(t -> em.createNativeQuery(
                        "update relationship_graph_nodes set character_id = ?1, character_linked_episode_no = 2 where id = ?2")
                .setParameter(1, characterId).setParameter(2, target).executeUpdate());
        UUID second = job(2);
        Context later = prepare(second);
        apply(second, later, action(later, Operation.UPDATE, Kind.COMPANIONSHIP, "함께 여행한다"));

        // Episode 1: the source is the profile's only registered node, so it takes the profile name.
        assertThat(graphNode(1, source).name()).isEqualTo("아라 린");
        assertThat(graphNode(1, target)).satisfies(n -> {
            assertThat(n.name()).isEqualTo(targetName);
            assertThat(n.profileRegistered()).isFalse();
        });
        // Episode 2: both are registered to the same profile; neither repeats the profile name.
        assertThat(graphNode(2, source)).satisfies(n -> {
            assertThat(n.name()).isEqualTo(sourceName);
            assertThat(n.profileRegistered()).isTrue();
        });
        assertThat(graphNode(2, target)).satisfies(n -> {
            assertThat(n.name()).isEqualTo(targetName);
            assertThat(n.profileRegistered()).isTrue();
        });
    }

    @Test
    @DisplayName("별칭 조회는 확정 후보가 많아도 scalar 조회 한 번이며 후보와 원 분석 Job을 적재하지 않는다")
    void aliasLookupIsScalarAndDoesNotHydrateCandidatesOrJobs() {
        UUID characterId = character("아라 린");
        UUID profileNode =
                profileNodeAt(1, "아라 린과 보라는 함께 길을 떠났다.", "아라 린", "보라", characterId);
        for (int i = 0; i < 15; i++) {
            confirmedAliasAt(2, "아라는 웃었다.", characterId, "아라");
            confirmedAliasAt(2, "아라는 웃었다.", character("관측되지 않은 인물 " + i), "관측되지 않은 인물 " + i);
        }
        String text = "아라와 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);

        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        Context context;
        try {
            context = prepareWith(third, text, observation("O1", text, "아라", "보라"));
            assertThat(statistics.getEntityStatistics(SettingCandidate.class.getName()).getLoadCount()).isZero();
            // Only the running job itself, once per prepare transaction (S3 read happens between).
            assertThat(statistics.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isEqualTo(2);
            assertThat(identityQueryExecutions(statistics)).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
        assertThat(boundNodeId(third, context.observations().getFirst().sourceNodeRefs().getFirst()))
                .isEqualTo(profileNode);
    }

    private String knownCharacterId(UUID jobId, String ref) {
        var known = analyses.findById(jobId).orElseThrow().getBindings()
                .path("nodes").path(ref).path("knownCharacterId");
        return known.isTextual() ? known.asText() : null;
    }

    private static String sourceRef(Context context) {
        return context.observations().getFirst().sourceNodeRefs().getFirst();
    }

    private static String targetRef(Context context) {
        return context.observations().getFirst().targetNodeRefs().getFirst();
    }

    @Test
    @DisplayName("GH385 그 회차까지 확정된 전체 이름에 포함되는 짧은 이름은 그 프로필 노드를 다시 쓴다")
    void containedNameReusesUniqueProfileNode() {
        UUID characterId = character("비요른 얀델");
        UUID profileNode =
                profileNodeAt(1, "비요른 얀델과 보라는 함께 길을 떠났다.", "비요른 얀델", "보라", characterId);
        long before = nodeCount();

        String text = "비요른과 보라는 다시 만났다.";
        UUID second = jobWithSource(2, text, false);
        Context context = prepareWith(second, text, observation("O1", text, "비요른", "보라"));

        String ref = sourceRef(context);
        assertThat(boundNodeId(second, ref)).isEqualTo(profileNode);
        assertThat(nodeCount()).isEqualTo(before);
        assertThat(contextNode(context, ref).name()).isEqualTo("비요른 얀델");
        assertThat(contextNode(context, ref).profileRegistered()).isTrue();
        assertThat(knownCharacterId(second, ref)).isEqualTo(characterId.toString());
        assertThat(knownCharacterId(second, targetRef(context))).isNull();
    }

    @Test
    @DisplayName("GH385 전체 이름이 뒤 회차에 확정되면 그 전 회차의 짧은 이름은 프로필에 해소하지 않는다")
    void containedNameIsNotResolvedBeforeFullNameIsConfirmed() {
        UUID characterId = character("비요른 얀델");
        UUID profileNode =
                profileNodeAt(5, "비요른 얀델과 다라는 함께 길을 떠났다.", "비요른 얀델", "다라", characterId);

        String text = "비요른과 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "비요른", "보라"));

        String ref = sourceRef(context);
        assertThat(boundNodeId(third, ref)).isNotEqualTo(profileNode);
        assertThat(contextNode(context, ref).name()).isEqualTo("비요른");
        assertThat(contextNode(context, ref).profileRegistered()).isFalse();
        assertThat(knownCharacterId(third, ref)).isNull();
        assertThat(context.nodes()).noneMatch(n -> n.name().equals("비요른 얀델"));
    }

    @ParameterizedTest(name = "existingNode={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("GH385 짧은 이름이 두 프로필의 확정 이름에 겹치면 노드를 만들거나 연결하지 않고 보류한다")
    void containedNameOverlappingTwoProfilesHolds(boolean existingNode) {
        UUID existing = null;
        if (existingNode) {
            String early = "비요른과 보라는 함께 길을 떠났다.";
            UUID first = jobWithSource(1, early, false);
            Context c1 = prepareWith(first, early, observation("O1", early, "비요른", "보라"));
            existing = boundNodeId(first, sourceRef(c1));
            apply(first, c1, add(c1, "O1", Kind.COMPANIONSHIP, "함께 여행한다"));
        }
        UUID bjorn = character("비요른 얀델"), other = character("비요른 라프손");
        confirmedAliasAt(1, "비요른 얀델은 웃었다.", bjorn, "비요른 얀델");
        confirmedAliasAt(2, "비요른 라프손은 웃었다.", other, "비요른 라프손");
        long before = nodeCount();

        String text = "비요른과 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "비요른", "보라"));

        assertThat(context.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(context.nodes()).extracting(n -> n.name()).containsExactly("보라");
        assertThat(nodeCount()).isEqualTo(existingNode ? before : 1);
        if (existingNode) assertProfileLink(existing, null, 1, null);
    }

    @Test
    @DisplayName("GH385 이미 만든 미연결 짧은 이름 노드는 발견 확정 없이도 고정 인물 정보로 반영 때 프로필 노드에 합쳐진다")
    void containedNameMergesExistingUnlinkedNodeAtFinalizeWithoutDiscovery() {
        String early = "비요른과 보라는 함께 길을 떠났다.";
        UUID first = jobWithSource(1, early, false);
        Context c1 = prepareWith(first, early, observation("O1", early, "비요른", "보라"));
        apply(first, c1, add(c1, "O1", Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID alias = boundNodeId(first, sourceRef(c1));
        UUID bora = boundNodeId(first, targetRef(c1));
        UUID characterId = character("비요른 얀델");
        UUID profile = profileNodeAt(2, "비요른 얀델과 다라는 함께 길을 떠났다.", "비요른 얀델", "다라", characterId);

        String text = "비요른과 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "비요른", "보라"));
        String ref = sourceRef(context);
        // The same-name node is already visible, so this context binds it and only freezes the profile.
        assertThat(boundNodeId(third, ref)).isEqualTo(alias);
        assertThat(knownCharacterId(third, ref)).isEqualTo(characterId.toString());
        // No discovery is confirmed in this job: the frozen identity alone merges the node.
        apply(third, context, action(context, Operation.UPDATE, Kind.COMPANIONSHIP, "다시 함께 여행한다"));

        assertMergedInto(alias, profile, 3, 1, "비요른");
        assertProfileLink(profile, characterId, 2, 2);
        Graph latest = service.getRelationshipGraph(owner, workId, 3);
        assertThat(latest.nodes()).extracting(n -> n.id()).doesNotContain(alias).contains(profile, bora);
        assertThat(latest.nodes()).filteredOn(n -> n.id().equals(profile)).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("비요른 얀델");
            assertThat(n.profileRegistered()).isTrue();
        });
        assertThat(latest.edges()).filteredOn(e -> endpoints(e).equals(java.util.Set.of(profile, bora)))
                .singleElement().extracting(Edge::summary).isEqualTo("다시 함께 여행한다");
        Graph past = service.getRelationshipGraph(owner, workId, 1);
        assertThat(past.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(profile, bora);
        assertThat(past.nodes()).filteredOn(n -> n.id().equals(profile)).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("비요른");
            assertThat(n.profileRegistered()).isFalse();
        });

        long before = nodeCount();
        String later = "비요른과 보라는 또 만났다.";
        UUID fourth = jobWithSource(4, later, false);
        Context next = prepareWith(fourth, later, observation("O1", later, "비요른", "보라"));
        assertThat(boundNodeId(fourth, sourceRef(next))).isEqualTo(profile);
        assertThat(nodeCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("GH385 같은 이름의 기존 노드가 다른 프로필에 연결돼 있으면 포함 규칙으로 해소해도 보류한다")
    void containedNameHeldWhenExistingNodeLinksAnotherProfile() {
        UUID other = character("늑대 전사");
        frozenCharacter("비요른", other);
        String early = "비요른과 보라는 함께 길을 떠났다.";
        UUID first = jobWithSource(1, early, true);
        Context c1 = prepareWith(first, early, observation("O1", early, "비요른", "보라"));
        UUID existing = boundNodeId(first, sourceRef(c1));
        apply(first, c1, add(c1, "O1", Kind.COMPANIONSHIP, "함께 여행한다"));
        assertProfileLink(existing, other, 1, 1);
        UUID characterId = character("비요른 얀델");
        confirmedAliasAt(2, "비요른 얀델은 웃었다.", characterId, "비요른 얀델");
        long before = nodeCount();

        String text = "비요른과 보라는 다시 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "비요른", "보라"));

        assertThat(context.observations().getFirst().sourceNodeRefs()).isEmpty();
        assertThat(context.nodes()).extracting(n -> n.name()).containsExactly("보라");
        assertThat(nodeCount()).isEqualTo(before);
        assertProfileLink(existing, other, 1, 1);
    }

    @Test
    @DisplayName("GH385 대명사와 1글자 이름은 포함 규칙으로 프로필에 해소하지 않는다")
    void containedNameSkipsPronounsAndOneCharacterNames() {
        UUID knight = character("그녀의 기사"), lin = character("린 아라");
        confirmedAliasAt(1, "그녀의 기사와 린 아라는 웃었다.", knight, "그녀의 기사");
        confirmedAliasAt(1, "그녀의 기사와 린 아라는 웃었다.", lin, "린 아라");

        String text = "그녀와 린은 다시 만났다.";
        UUID second = jobWithSource(2, text, false);
        Context context = prepareWith(second, text, observation("O1", text, "그녀", "린"));

        for (String ref : List.of(sourceRef(context), targetRef(context))) {
            assertThat(contextNode(context, ref).profileRegistered()).isFalse();
            assertThat(knownCharacterId(second, ref)).isNull();
        }
        assertThat(context.nodes()).extracting(n -> n.name()).containsExactlyInAnyOrder("그녀", "린");
        assertThat(nodeCount()).isEqualTo(2);
    }

    @ParameterizedTest(name = "ordered={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("GH385 순서 분석 대표 이름과 확정 발견 이름은 포함 해소와 문맥 노드의 고정 인물 정보에 같은 결과를 낸다")
    void containedNameResolvesWithOrderedRepresentativeName(boolean ordered) {
        String early = "비요른과 보라와 다라는 함께 길을 떠났다.";
        UUID first = jobWithSource(1, early, false);
        Context c1 = prepareWith(first, early,
                observation("O1", early, "비요른", "보라"), observation("O2", early, "다라", "보라"));
        apply(first, c1, add(c1, "O1", Kind.COMPANIONSHIP, "함께 여행한다"),
                add(c1, "O2", Kind.FRIENDSHIP, "친구다"));
        UUID bjornNode = boundNodeId(first, sourceRef(c1));
        UUID daraNode = boundNodeId(first, c1.observations().get(1).sourceNodeRefs().getFirst());
        UUID bjorn = character("비요른 얀델"), dara = character("다라");
        if (ordered) {
            var input = new ObjectMapper().createObjectNode();
            var characters = input.putArray("characters");
            characters.addObject().put("name", "비요른 얀델").put("actualCharacterId", bjorn.toString());
            characters.addObject().put("name", "다라").put("actualCharacterId", dara.toString());
            doReturn(input).when(runState).getInputState(any());
        } else {
            confirmedAliasAt(1, early, bjorn, "비요른 얀델");
            confirmedAliasAt(1, early, dara, "다라");
        }

        String text = "비요른과 보라는 다시 만났다.";
        UUID second = jobWithSource(2, text, ordered);
        Context context = prepareWith(second, text, observation("O1", text, "비요른", "보라"));
        String ref = sourceRef(context);
        assertThat(boundNodeId(second, ref)).isEqualTo(bjornNode);
        assertThat(knownCharacterId(second, ref)).isEqualTo(bjorn.toString());
        // "다라" is not observed here; its node enters only through the prior relationship.
        String daraRef = context.nodes().stream().map(n -> n.ref())
                .filter(r -> boundNodeId(second, r).equals(daraNode)).findFirst().orElseThrow();
        assertThat(knownCharacterId(second, daraRef)).isEqualTo(dara.toString());
        apply(second, context, action(context, Operation.UPDATE, Kind.COMPANIONSHIP, "다시 함께 여행한다"));

        assertProfileLink(bjornNode, bjorn, 1, 2);
        assertProfileLink(daraNode, dara, 1, 2);
    }

    @Test
    @DisplayName("GH385 포함 비교용 확정 이름 목록과 현재 프로필 이름은 각각 scalar 조회 한 번이며 후보와 프로필 엔티티를 적재하지 않는다")
    void containedNameLookupIsScalarAndDoesNotHydrateCandidatesOrProfiles() {
        UUID characterId = character("비요른 얀델");
        UUID profileNode =
                profileNodeAt(1, "비요른 얀델과 보라는 함께 길을 떠났다.", "비요른 얀델", "보라", characterId);
        for (int i = 0; i < 30; i++)
            confirmedAliasAt(1, "비요른 얀델은 웃었다.", character("관측되지 않은 인물 " + i), "관측되지 않은 인물 " + i);
        String text = "비요른과 보라는 다시 만났다.";
        UUID second = jobWithSource(2, text, false);

        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        Context context;
        try {
            context = prepareWith(second, text, observation("O1", text, "비요른", "보라"));
            assertThat(statistics.getEntityStatistics(SettingCandidate.class.getName()).getLoadCount()).isZero();
            assertThat(statistics.getEntityStatistics(WorkCharacter.class.getName()).getLoadCount()).isZero();
            assertThat(statistics.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isEqualTo(2);
            assertThat(identityQueryExecutions(statistics)).isEqualTo(1);
            // Current profile names guard collisions through one scalar query as well.
            assertThat(profileNameQueryExecutions(statistics)).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
        assertThat(boundNodeId(second, sourceRef(context))).isEqualTo(profileNode);
    }

    private long profileNameQueryExecutions(org.hibernate.stat.Statistics statistics) {
        return java.util.Arrays.stream(statistics.getQueries())
                .filter(q -> q.contains("WorkCharacter") && q.contains("character.name as name") && !q.contains(":ids"))
                .mapToLong(q -> statistics.getQueryStatistics(q).getExecutionCount())
                .sum();
    }

    @ParameterizedTest(name = "ordered={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("GH385 현재 프로필 이름은 다른 프로필과의 같은 이름·포함 충돌을 보류로 바꾸고 순서 분석 입력과 같은 결과를 낸다")
    void currentProfileNamesHoldCollisionsLikeOrderedInput(boolean ordered) {
        UUID hans = character("한스"), hansHodge = character("한스 호지"), bjorn = character("비요른 얀델");
        // The "한스 호지" discovery points at profile "한스" while a separate "한스 호지" profile exists.
        String early = "한스와 한스 호지와 얀델과 비요른 얀델은 웃었다.";
        confirmedAliasAt(1, early, hans, "한스");
        confirmedAliasAt(1, early, hans, "한스 호지");
        confirmedAliasAt(1, early, bjorn, "얀델");
        confirmedAliasAt(1, early, bjorn, "비요른 얀델");
        if (ordered) {
            var input = new ObjectMapper().createObjectNode();
            var characters = input.putArray("characters");
            characters.addObject().put("name", "한스").put("actualCharacterId", hans.toString());
            characters.addObject().put("name", "한스 호지").put("actualCharacterId", hansHodge.toString());
            characters.addObject().put("name", "비요른 얀델").put("actualCharacterId", bjorn.toString());
            doReturn(input).when(runState).getInputState(any());
        }

        String text = "한스 호지와 호지와 한스와 비요른은 보라를 만났다.";
        UUID second = jobWithSource(2, text, ordered);
        Context context = prepareWith(second, text,
                observation("O1", text, "한스 호지", "보라"),
                observation("O2", text, "호지", "보라"),
                observation("O3", text, "한스", "보라"),
                observation("O4", text, "비요른", "보라"));

        var observed = context.observations();
        // Same exact name on two profiles, and a short name contained in both: held, no node.
        assertThat(observed.get(0).sourceNodeRefs()).isEmpty();
        assertThat(observed.get(1).sourceNodeRefs()).isEmpty();
        // The exact name wins although "한스 호지" (another profile) contains it.
        String hansRef = observed.get(2).sourceNodeRefs().getFirst();
        assertThat(knownCharacterId(second, hansRef)).isEqualTo(hans.toString());
        assertThat(contextNode(context, hansRef).profileRegistered()).isTrue();
        String bjornRef = observed.get(3).sourceNodeRefs().getFirst();
        assertThat(knownCharacterId(second, bjornRef)).isEqualTo(bjorn.toString());
        assertThat(contextNode(context, bjornRef).profileRegistered()).isTrue();
        assertThat(context.nodes()).extracting(n -> n.name()).containsExactlyInAnyOrder("한스", "비요른", "보라");
        assertThat(nodeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("GH385 발견 기록 없는 현재 프로필 이름에만 겹치는 짧은 이름은 미연결 새 노드로 둔다")
    void currentProfileNameAloneDoesNotResolve() {
        character("비요른 얀델");
        String text = "비요른과 보라는 다시 만났다.";
        UUID first = jobWithSource(1, text, false);
        Context context = prepareWith(first, text, observation("O1", text, "비요른", "보라"));

        String ref = sourceRef(context);
        assertThat(contextNode(context, ref).name()).isEqualTo("비요른");
        assertThat(contextNode(context, ref).profileRegistered()).isFalse();
        assertThat(knownCharacterId(first, ref)).isNull();
        assertThat(nodeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("GH385 보관한 프로필의 확정 이름은 정확 일치와 포함 규칙 어느 쪽으로도 관계 노드를 해소하지 않는다")
    void archivedProfileNameIsNotAnIdentity() {
        UUID characterId = character("아라 린");
        confirmedAliasAt(1, "아라 린과 늑대는 웃었다.", characterId, "아라 린");
        confirmedAliasAt(1, "아라 린과 늑대는 웃었다.", characterId, "늑대");
        tx.executeWithoutResult(t -> em.find(WorkCharacter.class, characterId).archive());

        String text = "늑대와 아라는 다시 만났다.";
        UUID second = jobWithSource(2, text, false);
        Context context = prepareWith(second, text, observation("O1", text, "늑대", "아라"));

        for (String ref : List.of(sourceRef(context), targetRef(context))) {
            assertThat(contextNode(context, ref).profileRegistered()).isFalse();
            assertThat(knownCharacterId(second, ref)).isNull();
            assertProfileLink(boundNodeId(second, ref), null, 2, null);
        }
        assertThat(nodeCount()).isEqualTo(2);
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

    private List<RelationshipGraphEvent> relationshipEvents() {
        return tx.execute(t -> em.createQuery(
                        "select e from RelationshipGraphEvent e where e.workId=:work"
                                + " order by e.episodeNo, e.createdAt, e.id",
                        RelationshipGraphEvent.class)
                .setParameter("work", workId).getResultList());
    }

    // 이전 서버가 저장한 문맥에는 manual-only dependency marker가 없고 후행 무효화만 남아 있다.
    private void invalidateLegacyContext(UUID id) {
        tx.executeWithoutResult(t -> {
            var analysis = analyses.findById(id).orElseThrow();
            ObjectNode bindings = (ObjectNode) analysis.getBindings().deepCopy();
            bindings.remove(List.of("manualDependencyVersion", "manualDependencyRevision", "sourceDependencies"));
            ReflectionTestUtils.setField(analysis, "bindings", bindings);
            analysis.invalidate();
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"unchanged", "job-source-key", "job-source-version", "job-source-null"})
    @DisplayName("관계도 원문 메타데이터 검증은 큰 Job을 로딩하지 않고 기존 가용성 판단을 유지한다")
    void graphChecksSourceMetadataWithoutHydratingAnalysisJobs(String mutation) {
        UUID id = job(1, true);
        Context prepared = prepare(id);
        apply(id, prepared, action(prepared, Operation.ADD, Kind.COMPANIONSHIP, "함께 이동한다"));
        if (!mutation.equals("unchanged")) {
            tx.executeWithoutResult(t -> {
                // 출처 열은 updatable=false이므로 실제 DB 불일치 fixture를 직접 만든다.
                String update = switch (mutation) {
                    case "job-source-key" -> "source_content_s3_key='source/old'";
                    case "job-source-version" -> "source_content_s3_version='old-version'";
                    case "job-source-null" -> "source_content_s3_version=null";
                    default -> throw new AssertionError(mutation);
                };
                assertThat(em.createNativeQuery("update analysis_jobs set " + update + " where id=:id")
                        .setParameter("id", id).executeUpdate()).isEqualTo(1);
            });
        }
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            if (mutation.equals("unchanged")) {
                assertThat(service.getRelationshipGraph(owner, workId, 1).edges()).hasSize(1);
            } else {
                assertThatThrownBy(() -> service.getRelationshipGraph(owner, workId, 1))
                        .isInstanceOfSatisfying(AppException.class, failure -> assertThat(failure.getResultCode())
                                .isEqualTo(org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_NOT_FOUND));
            }
            assertThat(statistics.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount()).isZero();
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
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
    @DisplayName("22화 비교 중 14화가 반영되어도 동결 문맥으로 조회·완료·확정한다")
    void earlierCompletionPreservesLaterPreparedContext() {
        UUID second = job(22);
        Context b = prepare(second);
        UUID first = job(14);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "14화 동행"));

        assertThat(service.getRelationshipAnalysis(second, lease)).contains(b);
        assertThat(prepare(second)).isEqualTo(b);
        verify(storage, times(2)).getText(anyString());
        apply(second, b, action(b, Operation.ADD, Kind.COMPANIONSHIP, "22화 동행"));
        assertThat(analyses.findById(second).orElseThrow().getStatus())
                .isEqualTo(AnalysisStatus.APPLIED);
        assertThat(service.getRelationshipGraph(owner, workId, null).availableEpisodes())
                .extracting(EpisodeRef::episodeNo)
                .containsExactly(14, 22);
        assertThat(service.getRelationshipGraph(owner, workId, null).edges())
                .singleElement().extracting(Edge::summary).isEqualTo("22화 동행");
    }

    @Test
    @DisplayName("뒤 회차 확정 후 앞 회차를 추가해도 같은 관계의 두 이력을 회차순으로 보존한다")
    void earlierCompletionPreservesAlreadyAppliedLaterManualJob() {
        UUID second = job(22);
        Context b = prepare(second);
        apply(second, b, action(b, Operation.ADD, Kind.COMPANIONSHIP, "22화 동행"));
        UUID edgeId = service.getRelationshipGraph(owner, workId, null).edges().getFirst().id();
        UUID first = job(14);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "14화 동행"));
        assertThat(service.getRelationshipGraph(owner, workId, null).availableEpisodes())
                .extracting(EpisodeRef::episodeNo)
                .containsExactly(14, 22);
        assertThat(analyses.findById(second).orElseThrow().getStatus())
                .isEqualTo(AnalysisStatus.APPLIED);
        assertThat(service.getRelationshipGraph(owner, workId, 14).edges())
                .singleElement().satisfies(e -> {
                    assertThat(e.id()).isEqualTo(edgeId);
                    assertThat(e.summary()).isEqualTo("14화 동행");
                });
        assertThat(service.getRelationshipGraph(owner, workId, 22).edges())
                .singleElement().satisfies(e -> {
                    assertThat(e.id()).isEqualTo(edgeId);
                    assertThat(e.summary()).isEqualTo("22화 동행");
                });
        tx.executeWithoutResult(t -> {
            service.finalizeRelationshipAnalysis(jobs.findById(first).orElseThrow());
            service.finalizeRelationshipAnalysis(jobs.findById(second).orElseThrow());
        });
        assertThat(relationshipEvents()).extracting(RelationshipGraphEvent::getEpisodeNo)
                .containsExactly(14, 22);
        assertThat(relationshipEvents()).extracting(RelationshipGraphEvent::getEdgeId)
                .containsOnly(edgeId);
    }

    @Test
    @DisplayName("22화에서 끝난 관계는 늦게 반영한 14화 ADD로 되살아나지 않는다")
    void laterEndRemainsEndedAfterLateEarlierAdd() {
        UUID original = job(20);
        Context seed = prepare(original);
        apply(original, seed, action(seed, Operation.ADD, Kind.COMPANIONSHIP, "20화 동행"));
        UUID second = job(22);
        Context later = prepare(second);
        apply(second, later, action(later, Operation.END, Kind.COMPANIONSHIP, "22화 동행 종료"));
        UUID first = job(14);
        Context earlier = prepare(first);
        assertThat(earlier.relationships()).isEmpty();
        apply(first, earlier, action(earlier, Operation.ADD, Kind.COMPANIONSHIP, "14화 동행"));

        assertThat(service.getRelationshipGraph(owner, workId, 14).edges())
                .singleElement().extracting(Edge::status).isEqualTo(EdgeStatus.ACTIVE);
        assertThat(service.getRelationshipGraph(owner, workId, null).edges())
                .singleElement().satisfies(e -> {
                    assertThat(e.status()).isEqualTo(EdgeStatus.ENDED);
                    assertThat(e.summary()).isEqualTo("22화 동행 종료");
                });
        assertThat(relationshipEvents()).extracting(RelationshipGraphEvent::getEpisodeNo)
                .containsExactly(14, 20, 22);
    }

    @Test
    @DisplayName("후행 MERGE도 독립된 현재 사실로 남아 앞 회차 수정 뒤 다시 분석하지 않는다")
    void laterMergeRemainsIndependentPresentEventAfterEarlierUpdate() {
        UUID original = job(1);
        Context seed = prepare(original);
        apply(original, seed, action(seed, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(22);
        Context later = prepare(second);
        apply(second, later, action(later, Operation.MERGE, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID first = job(14);
        Context earlier = prepare(first);
        apply(first, earlier, action(earlier, Operation.UPDATE, Kind.COMPANIONSHIP, "14화 별도의 동행"));

        assertThat(analyses.findById(second).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.APPLIED);
        assertThat(service.getRelationshipGraph(owner, workId, 14).edges())
                .singleElement().extracting(Edge::summary).isEqualTo("14화 별도의 동행");
        assertThat(service.getRelationshipGraph(owner, workId, 22).edges())
                .singleElement().extracting(Edge::summary).isEqualTo("함께 여행한다");
        assertThat(relationshipEvents()).extracting(RelationshipGraphEvent::getEpisodeNo)
                .containsExactly(1, 14, 22);
    }

    @Test
    @DisplayName("구버전 후행 INVALIDATED 재시도는 자기 관측만 재사용하고 원문을 재추출하지 않는다")
    void legacyInvalidatedRetryReusesOnlyOwnObservationsWithoutSourceRead() {
        UUID original = job(1);
        Context seed = prepare(original);
        apply(original, seed, new Decision(List.of("O1"), Operation.HOLD, null, null,
                null, null, null, null, null, null, TemporalScope.UNKNOWN, "새 근거를 기다린다"));
        UUID second = job(22);
        Context frozen = prepare(second);
        assertThat(frozen.heldObservations()).hasSize(1);
        invalidateLegacyContext(second);
        UUID first = job(14);
        Context earlier = prepare(first);
        apply(first, earlier, action(earlier, Operation.ADD, Kind.COMPANIONSHIP, "14화 동행"));
        clearInvocations(storage);

        Context resumed = service.getRelationshipAnalysis(second, lease).orElseThrow();
        assertThat(resumed.status()).isEqualTo(AnalysisStatus.PREPARED);
        assertThat(resumed.contextToken()).isNotEqualTo(frozen.contextToken());
        assertThat(resumed.sourceHash()).isEqualTo(frozen.sourceHash());
        assertThat(resumed.sourceEpisodeNo()).isEqualTo(22);
        assertThat(resumed.observations()).hasSize(1);
        assertThat(resumed.observations().getFirst().ref()).isEqualTo("O1");
        assertThat(resumed.observations().getFirst().evidence())
                .isEqualTo(frozen.observations().getFirst().evidence());
        assertThat(resumed.heldObservations()).hasSize(1);
        assertThat(resumed.relationships()).hasSize(1);
        assertThat(service.getRelationshipAnalysis(second, lease)).contains(resumed);
        assertThat(prepare(second)).isEqualTo(resumed);
        verify(storage, never()).getText(anyString());
        apply(second, resumed, action(resumed, Operation.UPDATE, Kind.COMPANIONSHIP, "22화 동행"));
        assertThat(analyses.findById(second).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.APPLIED);
    }

    @Test
    @DisplayName("구버전 완료 문맥도 자기 관측으로 재준비하되 부분 완료 계약과 원문 무조회는 보존한다")
    void legacyInvalidatedCompletedRetryRepreparesOwnObservationsWithoutSourceRead() {
        UUID id = job(22);
        tx.executeWithoutResult(t -> jobs.findById(id).orElseThrow().enableRelationshipPartialResults());
        Context frozen = prepare(id);
        UUID sourceNode = boundNodeId(id, frozen.observations().getFirst().sourceNodeRefs().getFirst());
        UUID targetNode = boundNodeId(id, frozen.observations().getFirst().targetNodeRefs().getFirst());
        service.completeRelationshipAnalysis(id, lease, new Complete(1, frozen.contextToken(),
                List.of(action(frozen, Operation.ADD, Kind.COMPANIONSHIP, "22화 동행"))));
        invalidateLegacyContext(id);
        assertThat(analyses.findById(id).orElseThrow().getDecisions()).isNotEmpty();
        clearInvocations(storage);

        Context resumed = service.getRelationshipAnalysis(id, lease).orElseThrow();
        assertThat(resumed.status()).isEqualTo(AnalysisStatus.PREPARED);
        assertThat(resumed.supportsPartialResults()).isTrue();
        assertThat(resumed.failures()).isEmpty();
        assertThat(resumed.contextToken()).isNotEqualTo(frozen.contextToken());
        assertThat(resumed.observations())
                .extracting(o -> tuple(o.ref(), o.source(), o.target(), o.summary(), o.evidence(), o.sourceEpisodeNo()))
                .containsExactlyElementsOf(frozen.observations().stream()
                        .map(o -> tuple(o.ref(), o.source(), o.target(), o.summary(), o.evidence(), o.sourceEpisodeNo()))
                        .toList());
        // N refs belong to the newly prepared context; the real character node identities persist.
        assertThat(boundNodeId(id, resumed.observations().getFirst().sourceNodeRefs().getFirst())).isEqualTo(sourceNode);
        assertThat(boundNodeId(id, resumed.observations().getFirst().targetNodeRefs().getFirst())).isEqualTo(targetNode);
        assertThat(analyses.findById(id).orElseThrow().getDecisions()).isNull();
        verify(storage, never()).getText(anyString());
        apply(id, resumed, action(resumed, Operation.ADD, Kind.COMPANIONSHIP, "22화 동행"));
        assertThat(relationshipEvents()).hasSize(1);
    }

    @Test
    @DisplayName("새 완료 문맥은 앞 회차가 뒤늦게 반영되어도 비교 결과를 재사용하여 확정한다")
    void earlierCompletionPreservesLaterCompletedContextAndDecisions() {
        UUID second = job(22);
        tx.executeWithoutResult(t -> jobs.findById(second).orElseThrow().enableRelationshipPartialResults());
        Context later = prepare(second);
        service.completeRelationshipAnalysis(second, lease, new Complete(1, later.contextToken(),
                List.of(action(later, Operation.ADD, Kind.COMPANIONSHIP, "22화 동행"))));
        var decisions = analyses.findById(second).orElseThrow().getDecisions().deepCopy();
        UUID first = job(14);
        Context earlier = prepare(first);
        apply(first, earlier, action(earlier, Operation.ADD, Kind.COMPANIONSHIP, "14화 동행"));
        clearInvocations(storage);

        Context resumed = service.getRelationshipAnalysis(second, lease).orElseThrow();
        assertThat(resumed.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(resumed.contextToken()).isEqualTo(later.contextToken());
        assertThat(resumed.failures()).isEmpty();
        assertThat(analyses.findById(second).orElseThrow().getDecisions()).isEqualTo(decisions);
        tx.executeWithoutResult(t -> service.finalizeRelationshipAnalysis(jobs.findById(second).orElseThrow()));
        verify(storage, never()).getText(anyString());
        assertThat(relationshipEvents()).extracting(RelationshipGraphEvent::getEpisodeNo)
                .containsExactly(14, 22);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"source-key", "source-version", "source-hash", "generation", "superseded", "purged"})
    @DisplayName("구버전 복구도 원문 식별자·세대·최신 작업·근거 파기 보호를 우회하지 않는다")
    void legacyInvalidatedRetryRejectsUnsafeSourcesAndSupersededJobs(String mutation) {
        UUID id = job(22);
        prepare(id);
        invalidateLegacyContext(id);
        if (mutation.equals("superseded")) {
            job(22);
        } else {
            tx.executeWithoutResult(t -> {
                AnalysisJob job = jobs.findById(id).orElseThrow();
                Episode episode = job.getEpisode();
                switch (mutation) {
                    case "source-key" -> episode.updateContentStorage("source/replaced", "v1", episode.getContentHash());
                    case "source-version" -> episode.updateContentStorage(episode.getContentS3Key(), "v2", episode.getContentHash());
                    case "source-hash" -> episode.updateContentStorage(episode.getContentS3Key(), "v1", storage.contentHash("교체한 합성 원문"));
                    case "generation" -> job.configureRelationship(job.getRelationshipGeneration() + 1, false);
                    case "purged" -> purge.purgeNarrativeTimeEvidence(episode.getId());
                    default -> throw new AssertionError(mutation);
                }
            });
        }
        clearInvocations(storage);
        assertThatThrownBy(() -> service.getRelationshipAnalysis(id, lease)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> prepare(id)).isInstanceOf(AppException.class);
        verify(storage, never()).getText(anyString());
        assertThat(relationshipEvents()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"correction", "deletion"})
    @DisplayName("구버전 재개도 문맥 준비 뒤 사용자가 정정하거나 삭제한 관계를 덮어쓰지 않는다")
    void legacyInvalidatedRetryPreservesManualConflict(String mutation) {
        UUID first = job(1);
        Context seed = prepare(first);
        apply(first, seed, action(seed, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(22);
        prepare(second);
        invalidateLegacyContext(second);
        Edge edge = service.getRelationshipGraph(owner, workId, 1).edges().getFirst();
        if (mutation.equals("correction"))
            service.correctRelationshipEdge(owner, workId, edge.id(), new Correction(edge.version(), "사용자 정정"));
        else service.deleteRelationshipEdge(owner, workId, edge.id(), edge.version());
        clearInvocations(storage);

        assertThatThrownBy(() -> service.getRelationshipAnalysis(second, lease)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> prepare(second)).isInstanceOf(AppException.class);
        verify(storage, never()).getText(anyString());
        assertThat(relationshipEvents()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"correction", "deletion"})
    @DisplayName("관계가 없던 구버전 문맥도 뒤늦게 생긴 같은 인물 쌍의 사용자 정정과 삭제를 우회하지 않는다")
    void legacyObservationOnlyRetryRejectsLateManualEdgeChanges(String mutation) {
        UUID second = job(22);
        Context frozen = prepare(second);
        assertThat(frozen.observations()).hasSize(1);
        assertThat(frozen.relationships()).isEmpty();
        invalidateLegacyContext(second);

        UUID first = job(14);
        Context earlier = prepare(first);
        apply(first, earlier, action(earlier, Operation.ADD, Kind.COMPANIONSHIP, "14화 동행"));
        Edge edge = service.getRelationshipGraph(owner, workId, 14).edges().getFirst();
        if (mutation.equals("correction"))
            service.correctRelationshipEdge(owner, workId, edge.id(), new Correction(edge.version(), "사용자 정정"));
        else service.deleteRelationshipEdge(owner, workId, edge.id(), edge.version());
        clearInvocations(storage);

        assertThatThrownBy(() -> service.getRelationshipAnalysis(second, lease)).isInstanceOf(AppException.class);
        assertThat(analyses.findById(second).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.INVALIDATED);
        assertThat(analyses.findById(second).orElseThrow().getContextToken()).isEqualTo(frozen.contextToken());
        verify(storage, never()).getText(anyString());
        assertThat(relationshipEvents()).extracting(RelationshipGraphEvent::getEpisodeNo).containsExactly(14);
        if (mutation.equals("correction"))
            assertThat(service.getRelationshipGraph(owner, workId, 14).edges())
                    .singleElement().extracting(Edge::summary).isEqualTo("사용자 정정");
        else assertThat(service.getRelationshipGraph(owner, workId, 14).edges()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"source-key", "source-version", "purged"})
    @DisplayName("후행 문맥이 참조한 앞 회차 원문이 바뀌거나 파기되면 재개와 완료를 거부한다")
    void copiedEarlierSourceProtectionStillRejectsStaleContext(String mutation) {
        UUID first = job(1);
        Context seed = prepare(first);
        apply(first, seed, action(seed, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(22);
        Context frozen = prepare(second);
        tx.executeWithoutResult(t -> {
            Episode episode = jobs.findById(first).orElseThrow().getEpisode();
            switch (mutation) {
                case "source-key" -> episode.updateContentStorage("source/replaced", "v1", episode.getContentHash());
                case "source-version" -> episode.updateContentStorage(episode.getContentS3Key(), "v2", episode.getContentHash());
                case "purged" -> purge.purgeNarrativeTimeEvidence(episode.getId());
                default -> throw new AssertionError(mutation);
            }
        });
        clearInvocations(storage);
        assertThatThrownBy(() -> service.getRelationshipAnalysis(second, lease)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.completeRelationshipAnalysis(second, lease,
                new Complete(1, frozen.contextToken(), List.of(action(frozen, Operation.UPDATE,
                        Kind.COMPANIONSHIP, "새 설명"))))).isInstanceOf(AppException.class);
        verify(storage, never()).getText(anyString());
    }

    @Test
    @DisplayName("추출 전 구버전 INVALIDATED는 문맥을 꾸며내지 않고 준비 전 상태로 복구한다")
    void legacyInvalidatedPendingRetryDoesNotInventCachedObservations() {
        UUID id = job(22);
        tx.executeWithoutResult(t -> analyses.findById(id).orElseThrow().invalidate());
        clearInvocations(storage);
        assertThat(service.getRelationshipAnalysis(id, lease)).isEmpty();
        assertThat(analyses.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
        verify(storage, never()).getText(anyString());
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
        assertThat(service.getRelationshipAnalysis(second, lease)).contains(future);
        assertThat(prepare(second)).isEqualTo(future);
        Graph firstGraph = service.getRelationshipGraph(owner, workId, 1);
        assertThat(firstGraph.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(ara, bora);
        assertThat(firstGraph.nodes()).allSatisfy(n -> assertThat(n.profileRegistered()).isFalse());
        assertThat(
                        service.getRelationshipEdge(
                                        owner, workId, firstGraph.edges().getFirst().id(), 1)
                                .evidence())
                .allSatisfy(e -> assertThat(e.episodeNo()).isEqualTo(1));

        apply(second, future, action(future, Operation.ADD, Kind.COMPANIONSHIP, "2화 동행"));
        Graph latest = service.getRelationshipGraph(owner, workId, 2);
        assertThat(latest.edges().getFirst().id()).isEqualTo(firstGraph.edges().getFirst().id());
        assertThat(latest.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(ara, bora);
        // GH382: from its registration episode the node shows the current profile name.
        assertThat(latest.nodes()).extracting(n -> n.name())
                .containsExactlyInAnyOrder("미래의 현재 프로필 이름", "보라");
        assertThat(service.getRelationshipGraph(owner, workId, 1).nodes())
                .allSatisfy(n -> assertThat(n.profileRegistered()).isFalse())
                .extracting(n -> n.name()).containsExactlyInAnyOrder("아라", "보라");
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
                .satisfies(n -> {
                    assertThat(n.profileRegistered()).isFalse();
                    assertThat(n.name()).isEqualTo("아라");
                });
        // GH382: the registered node shows the current profile name, not the observed one.
        assertThat(service.getRelationshipGraph(owner, workId, 2).nodes())
                .filteredOn(n -> n.id().equals(nodeId))
                .singleElement()
                .satisfies(
                        n -> {
                            assertThat(n.profileRegistered()).isTrue();
                            assertThat(n.name()).isEqualTo("현재 프로필의 다른 이름");
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"correction", "deletion"})
    @DisplayName("준비 뒤 사용자 정정과 삭제는 새 독립 문맥에서도 조회와 완료를 거부한다")
    void userCorrectionAfterPrepareRejectsStaleComparison(String mutation) {
        UUID first = job(1);
        Context a = prepare(first);
        apply(first, a, action(a, Operation.ADD, Kind.COMPANIONSHIP, "함께 여행한다"));
        UUID second = job(2);
        Context b = prepare(second);
        Edge edge = service.getRelationshipGraph(owner, workId, 1).edges().getFirst();
        if (mutation.equals("correction"))
            service.correctRelationshipEdge(
                    owner, workId, edge.id(), new Correction(edge.version(), "사용자가 바로잡은 설명"));
        else service.deleteRelationshipEdge(owner, workId, edge.id(), edge.version());
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

    // ---------------------------------------------------------------------------------------
    // GH391 관계도 1회 정리

    /** 정리 전 운영 데이터 모양: 1화 미연결 "비요른"과 2화 프로필 노드 "비요른 얀델"이 같은 slot 선을 따로 가진다. */
    private record AliasSplit(UUID characterId, UUID alias, UUID profile, UUID bora, UUID aliasEdge,
            UUID movedEdge, UUID profileEdge) {}

    private AliasSplit aliasSplit(boolean reversedProfileEdge) {
        String first = "비요른과 보라는 함께 길을 떠났다.";
        UUID j1 = jobWithSource(1, first, false);
        Context c1 = prepareWith(j1, first, observation("O1", first, "비요른", "보라"));
        apply(j1, c1, add(c1, "O1", Kind.COMPANIONSHIP, "함께 여행한다"), add(c1, "O1", Kind.FRIENDSHIP, "친구다"));
        UUID alias = boundNodeId(j1, sourceRef(c1)), bora = boundNodeId(j1, targetRef(c1));
        // The profile is created after 1화, so 1화 never knew the full name: a legacy split.
        UUID characterId = character("비요른 얀델");
        String second = "비요른 얀델과 보라는 다시 만났다.";
        UUID j2 = jobWithSource(2, second, false);
        Context c2 = prepareWith(j2, second, reversedProfileEdge
                ? observation("O1", second, "보라", "비요른 얀델")
                : observation("O1", second, "비요른 얀델", "보라"));
        confirmDiscovery(j2, characterId, "비요른 얀델");
        apply(j2, c2, add(c2, "O1", Kind.COMPANIONSHIP, "다시 함께 여행한다"));
        UUID profile = boundNodeId(j2, reversedProfileEdge ? targetRef(c2) : sourceRef(c2));
        assertThat(profile).isNotEqualTo(alias);
        List<Edge> past = service.getRelationshipGraph(owner, workId, 1).edges();
        UUID aliasEdge = past.stream().filter(e -> e.kind() == Kind.COMPANIONSHIP).findFirst().orElseThrow().id();
        UUID movedEdge = past.stream().filter(e -> e.kind() == Kind.FRIENDSHIP).findFirst().orElseThrow().id();
        UUID profileEdge = service.getRelationshipGraph(owner, workId, 2).edges().stream()
                .filter(e -> endpoints(e).equals(java.util.Set.of(profile, bora))).findFirst().orElseThrow().id();
        return new AliasSplit(characterId, alias, profile, bora, aliasEdge, movedEdge, profileEdge);
    }

    private List<org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge> graphEdges() {
        return tx.execute(t -> {
            em.clear();
            return em.createQuery("select e from RelationshipGraphEdge e where e.workId=:work order by e.id",
                            org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge.class)
                    .setParameter("work", workId).getResultList();
        });
    }

    /** Every stored column of the work's graph rows, for a byte-for-byte unchanged check. */
    private List<String> graphRows() {
        return tx.execute(t -> {
            em.clear();
            List<String> rows = new java.util.ArrayList<>();
            em.createQuery("select n from RelationshipGraphNode n where n.workId=:work order by n.id",
                            RelationshipGraphNode.class).setParameter("work", workId).getResultList()
                    .forEach(n -> rows.add(String.join("|", "N", "" + n.getId(), "" + n.getCharacterId(),
                            "" + n.getCharacterLinkedEpisodeNo(), n.getName(), "" + n.getFirstEpisodeNo(),
                            "" + n.getMergedIntoNodeId(), "" + n.getMergedAtEpisodeNo(), "" + n.getUpdatedAt())));
            em.createQuery("select e from RelationshipGraphEdge e where e.workId=:work order by e.id",
                            org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge.class)
                    .setParameter("work", workId).getResultList()
                    .forEach(e -> rows.add(String.join("|", "E", "" + e.getId(), "" + e.getSourceNodeId(),
                            "" + e.getTargetNodeId(), e.getCanonicalKey(), "" + e.getVersion(), "" + e.isSuppressed(),
                            "" + e.getCorrectedSummary(), "" + e.getUpdatedAt())));
            em.createQuery("select e from RelationshipGraphEvent e where e.workId=:work order by e.id",
                            RelationshipGraphEvent.class).setParameter("work", workId).getResultList()
                    .forEach(e -> rows.add(String.join("|", "V", "" + e.getId(), "" + e.getEdgeId(),
                            e.getSourceName(), e.getTargetName(), "" + e.getEvidence(), "" + e.getUpdatedAt())));
            return rows;
        });
    }

    private String canonicalKey(UUID source, UUID target, Kind kind, Direction direction) {
        return hasher.hash(org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphRules
                .canonical(source.toString(), target.toString(), Axis.SOCIAL, kind, direction));
    }

    private static void assertConflict(Throwable failure, String reason) {
        assertThat(failure).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getResultCode()).isEqualTo(
                    org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT);
            assertThat(e.getMessage()).contains(reason);
        });
    }

    @Test
    @DisplayName("GH391 dry-run은 보고서만 만들고 노드·선·event·version·수정 시각을 하나도 바꾸지 않는다")
    void cleanupDryRunWritesNothing() {
        AliasSplit split = aliasSplit(false);
        List<String> before = graphRows();

        var first = cleanup.cleanup(workId, false);
        var second = cleanup.cleanup(workId, false);

        assertThat(graphRows()).isEqualTo(before);
        assertThat(second).isEqualTo(first);
        assertThat(first.apply()).isFalse();
        assertThat(first.applied()).isFalse();
        assertThat(first.cutoffEpisodeNo()).isEqualTo(2);
        assertThat(first.blockers()).isEmpty();
        assertThat(first.counts().nodesMergedUnlinked()).isEqualTo(1);
        assertThat(first.counts().edgesMoved()).isEqualTo(1);
        assertThat(first.counts().edgesMerged()).isEqualTo(1);
        assertThat(first.counts().eventsMoved()).isEqualTo(1);
        assertThat(first.before()).isEqualTo(
                new org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.Totals(3, 3, 3, 3));
        assertThat(first.after()).isEqualTo(
                new org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.Totals(3, 2, 2, 3));
        assertThat(first.nodes()).singleElement().satisfies(n -> {
            assertThat(n.nodeId()).isEqualTo(split.alias());
            assertThat(n.name()).isEqualTo("비요른");
            assertThat(n.action()).isEqualTo("MERGE_UNLINKED");
            assertThat(n.profileName()).isEqualTo("비요른 얀델");
            assertThat(n.targetNodeId()).isEqualTo(split.profile());
            assertThat(n.targetNodeName()).isEqualTo("비요른 얀델");
        });
        assertThat(first.edgeGroups()).singleElement().satisfies(g -> {
            assertThat(g.survivorEdgeId()).isEqualTo(split.profileEdge());
            assertThat(g.mergedEdgeIds()).containsExactly(split.aliasEdge());
        });
    }

    @Test
    @DisplayName("GH391 적용은 별칭 노드의 선을 프로필 노드로 옮기고 같은 slot을 하나로 합치며 과거 화면의 관측 이름은 유지한다")
    void cleanupMovesAliasEdgesOntoProfileNodeAndMergesSlots() {
        AliasSplit split = aliasSplit(false);
        assertThat(service.getRelationshipGraph(owner, workId, 2).totalEdgeCount()).isEqualTo(3);

        var report = cleanup.cleanup(workId, true);

        assertThat(report.applied()).isTrue();
        assertMergedInto(split.alias(), split.profile(), 2, 1, "비요른");
        assertProfileLink(split.profile(), split.characterId(), 2, 2);
        var stored = graphEdges();
        assertThat(stored).extracting(e -> e.getId())
                .containsExactlyInAnyOrder(split.profileEdge(), split.movedEdge());
        assertThat(stored).allSatisfy(e -> {
            assertThat(java.util.Set.of(e.getSourceNodeId(), e.getTargetNodeId()))
                    .containsExactlyInAnyOrder(split.profile(), split.bora());
            assertThat(e.getCanonicalKey()).isEqualTo(
                    canonicalKey(e.getSourceNodeId(), e.getTargetNodeId(), e.getKind(), Direction.MUTUAL));
        });
        assertThat(relationshipEvents()).hasSize(3).allSatisfy(e ->
                assertThat(e.getEdgeId()).isIn(split.profileEdge(), split.movedEdge()));
        assertThat(relationshipEvents()).filteredOn(e -> e.getEdgeId().equals(split.profileEdge()))
                .extracting(RelationshipGraphEvent::getEpisodeNo).containsExactly(1, 2);

        Graph latest = service.getRelationshipGraph(owner, workId, 2);
        assertThat(latest.totalEdgeCount()).isEqualTo(2);
        assertThat(latest.nodes()).extracting(n -> n.id()).containsExactlyInAnyOrder(split.profile(), split.bora());
        assertThat(latest.nodes()).filteredOn(n -> n.id().equals(split.profile())).singleElement()
                .satisfies(n -> {
                    assertThat(n.name()).isEqualTo("비요른 얀델");
                    assertThat(n.profileRegistered()).isTrue();
                });
        Graph past = service.getRelationshipGraph(owner, workId, 1);
        assertThat(past.edges()).extracting(Edge::id)
                .containsExactlyInAnyOrder(split.profileEdge(), split.movedEdge());
        assertThat(past.nodes()).filteredOn(n -> n.id().equals(split.profile())).singleElement()
                .satisfies(n -> {
                    assertThat(n.name()).isEqualTo("비요른");
                    assertThat(n.profileRegistered()).isFalse();
                });
        assertThatThrownBy(() -> service.getRelationshipEdge(owner, workId, split.aliasEdge(), 2))
                .isInstanceOfSatisfying(AppException.class, failure -> assertThat(failure.getResultCode())
                        .isEqualTo(org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_NOT_FOUND));
        assertThat(service.getRelationshipEdge(owner, workId, split.profileEdge(), 2).evidence()).hasSize(2);
    }

    @Test
    @DisplayName("GH391 생존 선과 반대 순서로 옮긴 event는 source/target 이름을 맞바꿔 과거 화면에서 이름이 뒤바뀌지 않는다")
    void cleanupSwapsMovedEventNamesToSurvivorOrientation() {
        AliasSplit split = aliasSplit(true);

        var report = cleanup.cleanup(workId, true);

        assertThat(report.counts().eventsNamesSwapped()).isEqualTo(1);
        assertThat(relationshipEvents()).filteredOn(e -> e.getEdgeId().equals(split.profileEdge()))
                .allSatisfy(e -> assertThat(e.getSourceName()).isEqualTo("보라"));
        Graph past = service.getRelationshipGraph(owner, workId, 1);
        assertThat(past.nodes()).filteredOn(n -> n.id().equals(split.profile())).singleElement()
                .extracting(n -> n.name()).isEqualTo("비요른");
        assertThat(past.nodes()).filteredOn(n -> n.id().equals(split.bora())).singleElement()
                .extracting(n -> n.name()).isEqualTo("보라");
    }

    @Test
    @DisplayName("GH391 합친 선은 삭제 억제·정정 설명을 잇고 version을 둘보다 올리며 이후 재분석의 ADD가 되살리지 않는다")
    void cleanupPreservesCorrectionSuppressionAndVersion() {
        AliasSplit split = aliasSplit(false);
        Edge aliasEdge = service.getRelationshipGraph(owner, workId, 2).edges().stream()
                .filter(e -> e.id().equals(split.aliasEdge())).findFirst().orElseThrow();
        service.deleteRelationshipEdge(owner, workId, aliasEdge.id(), aliasEdge.version());
        Edge profileEdge = service.getRelationshipGraph(owner, workId, 2).edges().stream()
                .filter(e -> e.id().equals(split.profileEdge())).findFirst().orElseThrow();
        service.correctRelationshipEdge(owner, workId, profileEdge.id(),
                new Correction(profileEdge.version(), "사용자가 정정한 동행"));
        long aliasVersion = aliasEdge.version() + 1, profileVersion = profileEdge.version() + 1;

        var report = cleanup.cleanup(workId, true);

        assertThat(report.edgeGroups()).singleElement().satisfies(g -> {
            assertThat(g.suppressed()).isTrue();
            assertThat(g.corrected()).isTrue();
            assertThat(g.correctionConflict()).isFalse();
        });
        assertThat(graphEdges()).filteredOn(e -> e.getId().equals(split.profileEdge())).singleElement()
                .satisfies(e -> {
                    assertThat(e.isSuppressed()).isTrue();
                    assertThat(e.getCorrectedSummary()).isEqualTo("사용자가 정정한 동행");
                    assertThat(e.getVersion()).isEqualTo(Math.max(aliasVersion, profileVersion) + 1);
                });
        assertThat(service.getRelationshipGraph(owner, workId, 2).edges())
                .noneMatch(e -> e.kind() == Kind.COMPANIONSHIP);

        String text = "비요른 얀델과 보라는 또 만났다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "비요른 얀델", "보라"));
        assertThat(boundNodeId(third, sourceRef(context))).isEqualTo(split.profile());
        assertThat(context.relationships()).noneMatch(r -> r.kind() == Kind.COMPANIONSHIP);
        apply(third, context, add(context, "O1", Kind.COMPANIONSHIP, "되살아나면 안 되는 동행"));
        assertThat(service.getRelationshipGraph(owner, workId, 3).edges())
                .noneMatch(e -> e.kind() == Kind.COMPANIONSHIP);
        assertThat(graphEdges()).filteredOn(e -> e.getKind() == Kind.COMPANIONSHIP).hasSize(1);
    }

    @Test
    @DisplayName("GH391 같은 분석이 두 선에 남긴 event는 근거를 하나로 합쳐 상세 근거 수를 보존한다")
    void cleanupMergesEvidenceOfSameAnalysis() {
        String text = "비요른은 보라를 도왔다. 비요른 얀델은 보라와 웃었다.";
        String firstQuote = "비요른은 보라를 도왔다.", secondQuote = "비요른 얀델은 보라와 웃었다.";
        int secondStart = text.codePointCount(0, text.indexOf(secondQuote));
        UUID id = jobWithSource(1, text, false);
        Context context = prepareWith(id, text,
                new Observation("O1", new Endpoint("비요른", "비요른"), new Endpoint("보라", "보라"), "돕는다",
                        List.of(new Evidence(0, firstQuote.codePointCount(0, firstQuote.length()), firstQuote))),
                new Observation("O2", new Endpoint("비요른 얀델", "비요른 얀델"), new Endpoint("보라", "보라"), "웃는다",
                        List.of(new Evidence(secondStart,
                                secondStart + secondQuote.codePointCount(0, secondQuote.length()), secondQuote))));
        UUID characterId = character("비요른 얀델");
        confirmDiscovery(id, characterId, "비요른 얀델");
        apply(id, context, add(context, "O1", Kind.COMPANIONSHIP, "돕는다"),
                add(context, "O2", Kind.COMPANIONSHIP, "함께 웃는다"));
        assertThat(service.getRelationshipGraph(owner, workId, 1).edges()).hasSize(2);

        var report = cleanup.cleanup(workId, true);

        assertThat(report.counts().eventsEvidenceMerged()).isEqualTo(1);
        assertThat(report.counts().eventsMoved()).isZero();
        assertThat(relationshipEvents()).singleElement().satisfies(e -> {
            assertThat(e.getAnalysisId()).isEqualTo(id);
            assertThat(e.getEvidence()).hasSize(2);
            assertThat(e.getSummary()).isEqualTo("함께 웃는다");
        });
        Edge merged = service.getRelationshipGraph(owner, workId, 1).edges().getFirst();
        assertThat(service.getRelationshipGraph(owner, workId, 1).edges()).hasSize(1);
        assertThat(merged.evidenceCount()).isEqualTo(2);
        assertThat(service.getRelationshipEdge(owner, workId, merged.id(), 1).evidence())
                .extracting(e -> e.quote()).containsExactlyInAnyOrder(firstQuote, secondQuote);
    }

    @Test
    @DisplayName("GH391 같은 프로필에 연결된 노드는 이름이 그 프로필로만 해소될 때만 합치고 프로필 연결은 대표 노드에만 남긴다")
    void cleanupMergesLinkedSplitNodeOnlyWhenNameResolves() {
        String text = "브라운 로트밀러와 로트밀러와 미샤 칼스타인과 테일론 칼스타인은 보라를 만났다.";
        UUID id = jobWithSource(1, text, false);
        Context context = prepareWith(id, text, observation("O1", text, "브라운 로트밀러", "보라"),
                observation("O2", text, "로트밀러", "보라"), observation("O3", text, "미샤 칼스타인", "보라"),
                observation("O4", text, "테일론 칼스타인", "보라"));
        apply(id, context, add(context, "O1", Kind.COMPANIONSHIP, "만난다"), add(context, "O2", Kind.COMPANIONSHIP, "만난다"),
                add(context, "O3", Kind.COMPANIONSHIP, "만난다"), add(context, "O4", Kind.TRUST, "믿는다"));
        UUID brown = character("브라운 로트밀러"), misha = character("미샤 칼스타인");
        confirmedAliasAt(1, text, brown, "브라운 로트밀러");
        confirmedAliasAt(1, text, misha, "미샤 칼스타인");
        confirmedAliasAt(1, text, misha, "테일론 칼스타인");
        java.util.Map<String, UUID> node = new java.util.HashMap<>();
        for (String ref : List.of("O1", "O2", "O3", "O4")) {
            var observation = context.observations().stream().filter(o -> o.ref().equals(ref)).findFirst().orElseThrow();
            node.put(ref, boundNodeId(id, observation.sourceNodeRefs().getFirst()));
        }
        // Already split production data: two nodes linked to each profile.
        tx.executeWithoutResult(t -> java.util.Map.of("O1", brown, "O2", brown, "O3", misha, "O4", misha)
                .forEach((ref, profile) -> em.createNativeQuery(
                                "update relationship_graph_nodes set character_id = ?1, character_linked_episode_no = 1 where id = ?2")
                        .setParameter(1, profile).setParameter(2, node.get(ref)).executeUpdate()));

        var report = cleanup.cleanup(workId, true);

        assertThat(report.counts().nodesMergedLinked()).isEqualTo(1);
        assertThat(report.nodes()).filteredOn(n -> n.nodeId().equals(node.get("O4"))).singleElement()
                .satisfies(n -> {
                    assertThat(n.action()).isEqualTo("SKIPPED");
                    assertThat(n.reason()).isEqualTo("NOT_REPRESENTATIVE_FORM");
                });
        assertMergedInto(node.get("O2"), node.get("O1"), 1, 1, "로트밀러");
        assertProfileLink(node.get("O1"), brown, 1, 1);
        assertProfileLink(node.get("O4"), misha, 1, 1);
        assertProfileLink(node.get("O3"), misha, 1, 1);
        Graph graph = service.getRelationshipGraph(owner, workId, 1);
        assertThat(graph.nodes()).extracting(n -> n.id()).doesNotContain(node.get("O2"));
        // The profile now has one registered node, which therefore shows the profile name.
        assertThat(graph.nodes()).filteredOn(n -> n.id().equals(node.get("O1"))).singleElement()
                .extracting(n -> n.name()).isEqualTo("브라운 로트밀러");
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Kind.COMPANIONSHIP).hasSize(2);
    }

    @Test
    @DisplayName("GH391 진행 중이거나 문맥이 남은 무효 분석이 있으면 적용을 409로 보류하고 dry-run은 사유를 보고한다")
    void cleanupRejectsWhileContextIsFrozen() {
        AliasSplit split = aliasSplit(false);
        UUID third = job(3);
        prepare(third);
        List<String> before = graphRows();

        assertThat(cleanup.cleanup(workId, false).blockers()).containsExactly("ANALYSIS_IN_PROGRESS");
        assertConflict(catchThrowable(() -> cleanup.cleanup(workId, true)), "ANALYSIS_IN_PROGRESS");
        tx.executeWithoutResult(t -> analyses.findById(third).orElseThrow().invalidate());
        assertThat(cleanup.cleanup(workId, false).blockers()).containsExactly("CONTEXT_FROZEN");
        assertConflict(catchThrowable(() -> cleanup.cleanup(workId, true)), "CONTEXT_FROZEN");

        assertThat(graphRows()).isEqualTo(before);
        tx.executeWithoutResult(t -> assertThat(em.find(RelationshipGraphNode.class, split.alias()).isMerged()).isFalse());
    }

    @Test
    @DisplayName("GH391 관계 백필을 아직 완료하지 않은 작품은 적용하지 않는다")
    void cleanupSkipsWorkInActiveBackfill() {
        aliasSplit(false);
        tx.executeWithoutResult(t -> em.persist(
                org.monitoring.catchholebackend.domain.relationship.entity.RelationshipBackfillWork
                        .create(workId, "relationship-backfill-20261030-v1")));
        List<String> before = graphRows();

        assertThat(cleanup.cleanup(workId, false).blockers()).containsExactly("BACKFILL_ACTIVE");
        assertConflict(catchThrowable(() -> cleanup.cleanup(workId, true)), "BACKFILL_ACTIVE");
        assertThat(graphRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("GH391 정리 뒤 새 분석은 합친 선을 문맥 관계로 받아 갱신하고 별칭 의존성 필드를 만들지 않는다")
    void finalizeAfterCleanupUsesConsolidatedEdges() {
        AliasSplit split = aliasSplit(false);
        cleanup.cleanup(workId, true);

        String text = "비요른과 보라는 다시 걸었다.";
        UUID third = jobWithSource(3, text, false);
        Context context = prepareWith(third, text, observation("O1", text, "비요른", "보라"));
        assertThat(boundNodeId(third, sourceRef(context))).isEqualTo(split.profile());
        var companion = context.relationships().stream().filter(r -> r.kind() == Kind.COMPANIONSHIP)
                .findFirst().orElseThrow();
        assertThat(UUID.fromString(analyses.findById(third).orElseThrow().getBindings()
                .path("relationships").path(companion.ref()).asText())).isEqualTo(split.profileEdge());
        assertThat(analyses.findById(third).orElseThrow().getBindings().has("aliasDependencyVersion")).isFalse();
        apply(third, context, action(context, Operation.UPDATE, Kind.COMPANIONSHIP, "3화 동행"));

        assertThat(service.getRelationshipGraph(owner, workId, 3).edges())
                .filteredOn(e -> e.kind() == Kind.COMPANIONSHIP).singleElement().satisfies(e -> {
                    assertThat(e.id()).isEqualTo(split.profileEdge());
                    assertThat(e.summary()).isEqualTo("3화 동행");
                });
        // The 3화 event names follow the context names, oriented by the survivor's endpoints.
        assertThat(relationshipEvents()).filteredOn(e -> e.getEpisodeNo() == 3).singleElement()
                .satisfies(e -> {
                    assertThat(e.getEdgeId()).isEqualTo(split.profileEdge());
                    assertThat(e.getSourceName()).isEqualTo("비요른 얀델");
                    assertThat(e.getTargetName()).isEqualTo("보라");
                });
    }

    @Test
    @DisplayName("GH391 정리로 합친 별칭 노드의 실패 쌍과 보류 관측은 다음 문맥에서 프로필 노드 쌍으로 계속 해소된다")
    void blockedPairAndHeldObservationSurviveCleanup() {
        String text = "비요른과 보라와 라라는 함께 길을 떠났다.";
        UUID first = jobWithSource(1, text, false);
        tx.executeWithoutResult(t -> jobs.findById(first).orElseThrow().enableRelationshipPartialResults());
        Context c1 = prepareWith(first, text,
                observation("O1", text, "비요른", "보라"), observation("O2", text, "비요른", "라라"));
        service.completeRelationshipAnalysis(first, lease, new Complete(1, c1.contextToken(),
                List.of(new Decision(List.of("O2"), Operation.HOLD, null, null, null, null, null, null, null,
                        null, TemporalScope.UNKNOWN, "관계를 확정할 근거가 부족하다")),
                List.of(new org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.GroupFailure(
                        List.of("O1"), "RELATIONSHIP_ENDPOINTS_UNGROUNDED"))));
        tx.executeWithoutResult(t -> service.finalizeRelationshipAnalysis(jobs.findById(first).orElseThrow()));
        UUID characterId = character("비요른 얀델");
        UUID profile = profileNodeAt(2, "비요른 얀델과 다라는 함께 길을 떠났다.", "비요른 얀델", "다라", characterId);

        var report = cleanup.cleanup(workId, true);
        assertThat(report.counts().nodesMergedUnlinked()).isEqualTo(1);

        String later = "비요른 얀델과 보라와 라라는 다시 만났다.";
        UUID third = jobWithSource(3, later, false);
        tx.executeWithoutResult(t -> jobs.findById(third).orElseThrow().enableRelationshipPartialResults());
        Context c3 = prepareWith(third, later,
                observation("O1", later, "비요른 얀델", "보라"), observation("O2", later, "비요른 얀델", "라라"));
        String profileRef = c3.observations().getFirst().sourceNodeRefs().getFirst();
        assertThat(boundNodeId(third, profileRef)).isEqualTo(profile);
        assertThat(c3.blockedPairs()).singleElement().satisfies(blocked -> {
            assertThat(blocked.firstFailedEpisodeNo()).isEqualTo(1);
            assertThat(java.util.Set.of(blocked.sourceNodeRef(), blocked.targetNodeRef()))
                    .containsExactlyInAnyOrder(profileRef, c3.observations().getFirst().targetNodeRefs().getFirst());
        });
        assertThat(c3.heldObservations()).singleElement().satisfies(held -> {
            assertThat(held.source().name()).isEqualTo("비요른");
            assertThat(held.sourceNodeRefs()).containsExactly(profileRef);
            assertThat(held.sourceEpisodeNo()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("GH391 두 번째 적용은 바꿀 것이 없고 정리는 분석·설정 후보·프로필·분석 Job 엔티티를 올리지 않는다")
    void cleanupIsIdempotentAndDoesNotHydrateAnalyses() {
        aliasSplit(false);
        var statistics = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse first;
        try {
            first = cleanup.cleanup(workId, true);
            for (Class<?> heavy : List.of(
                    org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis.class,
                    SettingCandidate.class, WorkCharacter.class, AnalysisJob.class))
                assertThat(statistics.getEntityStatistics(heavy.getName()).getLoadCount()).as(heavy.getSimpleName())
                        .isZero();
            System.out.println("GH391 cleanup statistics: prepared=" + statistics.getPrepareStatementCount()
                    + " queries=" + statistics.getQueryExecutionCount()
                    + " entityLoads=" + statistics.getEntityLoadCount()
                    + " nodeLoads=" + statistics.getEntityStatistics(RelationshipGraphNode.class.getName()).getLoadCount()
                    + " edgeLoads=" + statistics.getEntityStatistics(
                            org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge.class.getName()).getLoadCount()
                    + " eventLoads=" + statistics.getEntityStatistics(RelationshipGraphEvent.class.getName()).getLoadCount()
                    + " workLoads=" + statistics.getEntityStatistics(Work.class.getName()).getLoadCount()
                    + " analysisLoads=" + statistics.getEntityStatistics(
                            org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis.class.getName()).getLoadCount()
                    + " candidateLoads=" + statistics.getEntityStatistics(SettingCandidate.class.getName()).getLoadCount()
                    + " characterLoads=" + statistics.getEntityStatistics(WorkCharacter.class.getName()).getLoadCount()
                    + " jobLoads=" + statistics.getEntityStatistics(AnalysisJob.class.getName()).getLoadCount());
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
        assertThat(first.applied()).isTrue();
        List<String> after = graphRows();

        var second = cleanup.cleanup(workId, true);

        assertThat(second.apply()).isTrue();
        assertThat(second.applied()).isFalse();
        assertThat(second.counts()).isEqualTo(
                new org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.Counts(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(second.before()).isEqualTo(first.after()).isEqualTo(second.after());
        assertThat(graphRows()).isEqualTo(after);
    }

    @Test
    @DisplayName("GH391 정리 뒤에도 작품 파기의 단일 노드 DELETE가 병합 노드 행까지 지운다")
    void purgeAfterCleanupDeletesAllRows() {
        aliasSplit(false);
        cleanup.cleanup(workId, true);
        assertThat(nodeCount()).isEqualTo(3);

        tx.executeWithoutResult(t -> {
            for (String table : List.of(
                    "relationship_graph_events", "relationship_graph_edges", "relationship_graph_nodes"))
                em.createNativeQuery("delete from " + table + " where work_id = ?1")
                        .setParameter(1, workId).executeUpdate();
        });
        assertThat(nodeCount()).isZero();
    }

    @Test
    @DisplayName("GH391 정리 엔드포인트는 내부 API 키가 있어야 하고 기본은 dry-run이다")
    void cleanupEndpointRequiresInternalKeyAndDefaultsToDryRun() throws Exception {
        aliasSplit(false);
        String body = "{\"workId\":\"" + workId + "\"}";
        String path = "/api/internal/v1/relationship-graph/cleanups";
        var json = org.springframework.http.MediaType.APPLICATION_JSON;
        List<String> before = graphRows();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(json).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-Internal-Api-Key", "local-development-internal-api-key")
                        .contentType(json).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.apply").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.counts.nodesMergedUnlinked").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.nodes[0].name").value("비요른"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-Internal-Api-Key", "local-development-internal-api-key")
                        .contentType(json).content("{\"workId\":\"" + UUID.randomUUID() + "\",\"apply\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-Internal-Api-Key", "local-development-internal-api-key")
                        .contentType(json).content("{\"apply\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(graphRows()).isEqualTo(before);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("X-Internal-Api-Key", "local-development-internal-api-key")
                        .contentType(json).content("{\"workId\":\"" + workId + "\",\"apply\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.applied").value(true));
    }
}
