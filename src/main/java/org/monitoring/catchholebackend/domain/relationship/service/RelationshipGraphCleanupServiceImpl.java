package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.RELATIONSHIP_GRAPH_CONFLICT;

import jakarta.persistence.EntityManager;

import lombok.extern.slf4j.Slf4j;

import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterNameRow;
import org.monitoring.catchholebackend.domain.character.repository.WorkCharacterRepository;
import org.monitoring.catchholebackend.domain.character.type.CharacterStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.Counts;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.EdgeGroupRow;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.ResolvedProfile;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.Totals;
import org.monitoring.catchholebackend.domain.relationship.dto.response.RelationshipGraphCleanupResponse.WarningRow;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.CleanupAbortedException;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.EdgeRow;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.EventSlot;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.Input;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.NodeActionKind;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.NodeRow;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipGraphCleanupRules.Plan;
import org.monitoring.catchholebackend.domain.relationship.processor.RelationshipNameRules;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphCleanupRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphCleanupRepository.EdgeEndpoints;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphCleanupRepository.EdgeEvents;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphCleanupRepository.EventTransfer;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphEdgeRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphEventRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipGraphNodeRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GH391 관계도 1회 정리. 계획은 {@link RelationshipGraphCleanupRules}가 scalar 행으로 세우고, 여기서는 작품 단위
 * 적재·게이트·적용·사후 검증만 한다. 분석·설정 후보·프로필·분석 Job 엔티티는 올리지 않는다. 적용은 노드 행을 지우지 않고
 * (동결 bindings가 옛 id를 가리킨다) 선 끝점·key·event만 옮기며, APPLIED 분석의 문맥·bindings는 건드리지 않는다.
 */
@Slf4j
@Service
public class RelationshipGraphCleanupServiceImpl implements RelationshipGraphCleanupService {
    private final WorkRepository works;
    private final RelationshipGraphNodeRepository nodes;
    private final RelationshipGraphEdgeRepository edges;
    private final RelationshipGraphEventRepository events;
    private final RelationshipGraphCleanupRepository cleanup;
    private final SettingCandidateRepository candidates;
    private final WorkCharacterRepository characters;
    private final ObjectStorageService storage;
    private final EntityManager entityManager;
    private final TransactionTemplate applying;
    private final TransactionTemplate previewing;

    public RelationshipGraphCleanupServiceImpl(
            WorkRepository works,
            RelationshipGraphNodeRepository nodes,
            RelationshipGraphEdgeRepository edges,
            RelationshipGraphEventRepository events,
            RelationshipGraphCleanupRepository cleanup,
            SettingCandidateRepository candidates,
            WorkCharacterRepository characters,
            ObjectStorageService storage,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.works = works;
        this.nodes = nodes;
        this.edges = edges;
        this.events = events;
        this.cleanup = cleanup;
        this.candidates = candidates;
        this.characters = characters;
        this.storage = storage;
        this.entityManager = entityManager;
        this.applying = new TransactionTemplate(transactionManager);
        // The dry-run reads one consistent snapshot like the public graph read and never writes.
        this.previewing = new TransactionTemplate(transactionManager);
        previewing.setReadOnly(true);
        previewing.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public RelationshipGraphCleanupResponse cleanup(UUID workId, boolean apply) {
        return (apply ? applying : previewing).execute(status -> run(workId, apply));
    }

    private RelationshipGraphCleanupResponse run(UUID workId, boolean apply) {
        if (apply) {
            // The same Work lock as prepare/finalize and user corrections/deletions.
            Work work = works.findByIdForUpdate(workId)
                    .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
            work.requireActive();
        } else if (!works.existsById(workId)) throw new AppException(WorkErrorCode.WORK_NOT_FOUND);
        var state = cleanup.state(workId);
        List<String> blockers = new ArrayList<>();
        // A frozen context's dependency hashes filter edges by their raw endpoints; moving them
        // under a PREPARED/COMPLETED or resumable INVALIDATED analysis would turn it into a 409.
        if (state.activeAnalyses() > 0) blockers.add("ANALYSIS_IN_PROGRESS");
        if (state.frozenContexts() > 0) blockers.add("CONTEXT_FROZEN");
        if (state.backfillActive()) blockers.add("BACKFILL_ACTIVE");
        if (apply && !blockers.isEmpty())
            throw new AppException(RELATIONSHIP_GRAPH_CONFLICT, "관계도 정리 보류(" + String.join(",", blockers) + ").");
        if (state.cutoff() == null) return empty(workId, apply, blockers);
        int cutoff = state.cutoff();

        List<RelationshipGraphNode> nodeEntities = nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(workId);
        List<RelationshipGraphEdge> edgeEntities = edges.findAllByWorkId(workId);
        List<EventSlot> slots = slots(workId);
        List<RelationshipNameRules.ProfileName> profiles =
                characters.findNamesByWorkIdAndStatus(workId, CharacterStatus.ACTIVE).stream()
                        .map(row -> new RelationshipNameRules.ProfileName(row.getId(), row.getName()))
                        .toList();
        Map<String, List<UUID>> identities = new LinkedHashMap<>();
        for (var identity : candidates.findConfirmedIdentities(
                workId, List.of(SettingCandidateKind.CHARACTER_DISCOVERY), cutoff))
            if (identity.getEntityName() != null)
                identities.computeIfAbsent(identity.getEntityName(), x -> new ArrayList<>())
                        .add(identity.getCharacterId());
        Map<String, List<UUID>> confirmed = RelationshipGraphCleanupRules.anchors(identities, profiles, state.ordered());
        Function<String, String> hasher = storage::contentHash;
        List<NodeRow> nodeRows = nodeEntities.stream().map(RelationshipGraphCleanupServiceImpl::row).toList();
        List<EdgeRow> edgeRows = edgeEntities.stream().map(RelationshipGraphCleanupServiceImpl::row).toList();
        Plan plan = plan(new Input(cutoff, nodeRows, edgeRows, slots, confirmed, profiles, hasher));
        Totals before = totals(nodeRows, edgeRows.size(), slots.size());
        Totals expected = new Totals(
                before.nodes(),
                before.canonicalNodes()
                        - (int) (plan.count(NodeActionKind.MERGE_UNLINKED) + plan.count(NodeActionKind.MERGE_LINKED)),
                before.edges() - plan.merges().size() - plan.selfLoops().size(),
                before.events() - plan.evidenceMerges().size() - plan.selfLoopEvents());
        Totals after = expected;
        boolean applied = apply && !plan.isEmpty();
        if (applied) {
            write(workId, cutoff, plan, nodeEntities, edgeEntities);
            after = verify(workId, cutoff, confirmed, profiles, hasher, expected);
        }
        var response = report(workId, apply, applied, cutoff, state.ordered(), blockers, plan, before, after,
                nodeRows, profiles);
        Counts c = response.counts();
        log.info("GH391 relationship graph cleanup workId={} apply={} applied={} cutoff={} linked={} mergedUnlinked={}"
                        + " mergedLinked={} held={} skipped={} edgesMoved={} edgesMerged={} edgesDeleted={}"
                        + " eventsMoved={} eventsSwapped={} evidenceMerged={} eventsDeleted={} warnings={} blockers={}",
                workId, apply, applied, cutoff, c.nodesLinked(), c.nodesMergedUnlinked(), c.nodesMergedLinked(),
                c.nodesHeld(), c.nodesSkipped(), c.edgesMoved(), c.edgesMerged(), c.edgesDeleted(), c.eventsMoved(),
                c.eventsNamesSwapped(), c.eventsEvidenceMerged(), c.eventsDeleted(), plan.warnings().size(),
                blockers.size());
        return response;
    }

    private static Plan plan(Input input) {
        try {
            return RelationshipGraphCleanupRules.plan(input);
        } catch (CleanupAbortedException aborted) {
            throw new AppException(RELATIONSHIP_GRAPH_CONFLICT, "관계도 정리 중단(" + aborted.code() + ").");
        }
    }

    /**
     * 순서가 중요하다. 노드·생존 선·근거 합치기는 JPA로 바꿔 flush하고, {@code updatable=false} 열은 JDBC로 바꾼다.
     * 같은 분석 event를 먼저 지워야 이동이 UNIQUE(analysis_id, edge_id)에 걸리지 않고, event를 옮긴 뒤에야 선을 지울 수
     * 있으며, 선을 지운 뒤에야 생존 선의 key를 바꿀 수 있다.
     */
    private void write(UUID workId, int cutoff, Plan plan, List<RelationshipGraphNode> nodeEntities,
            List<RelationshipGraphEdge> edgeEntities) {
        Map<UUID, RelationshipGraphNode> nodeById =
                nodeEntities.stream().collect(Collectors.toMap(RelationshipGraphNode::getId, n -> n));
        for (var action : plan.nodes()) {
            RelationshipGraphNode node = nodeById.get(action.nodeId());
            boolean changed = switch (action.kind()) {
                case LINK -> {
                    node.link(action.profileId(), cutoff);
                    yield action.profileId().equals(node.getCharacterId());
                }
                case MERGE_UNLINKED -> node.mergeInto(action.targetNodeId(), cutoff);
                case MERGE_LINKED -> node.consolidateInto(action.targetNodeId(), cutoff);
                case HELD, SKIPPED -> true;
            };
            if (!changed) throw new IllegalStateException("GH391 cleanup node action was not applicable");
        }
        Map<UUID, RelationshipGraphEdge> edgeById =
                edgeEntities.stream().collect(Collectors.toMap(RelationshipGraphEdge::getId, e -> e));
        for (var merge : plan.merges()) edgeById.get(merge.survivorId()).absorb(edgeById.get(merge.edgeId()));
        if (!plan.evidenceMerges().isEmpty()) {
            Set<UUID> ids = new HashSet<>();
            plan.evidenceMerges().forEach(m -> {
                ids.add(m.eventId());
                ids.add(m.intoEventId());
            });
            Map<UUID, RelationshipGraphEvent> eventById = events.findAllById(ids).stream()
                    .collect(Collectors.toMap(RelationshipGraphEvent::getId, e -> e));
            for (var merge : plan.evidenceMerges()) {
                RelationshipGraphEvent duplicate = eventById.get(merge.eventId());
                eventById.get(merge.intoEventId()).appendEvidence(duplicate.getEvidence());
                events.delete(duplicate);
            }
        }
        entityManager.flush();
        LocalDateTime now = LocalDateTime.now();
        List<EventTransfer> transfers = plan.merges().stream()
                .map(m -> new EventTransfer(m.edgeId(), m.survivorId(), m.swapNames(), m.movedEvents()))
                .toList();
        cleanup.swapEventNames(workId, transfers, now);
        cleanup.moveEvents(workId, transfers, now);
        cleanup.deleteEvents(workId, plan.selfLoops().stream()
                .map(s -> new EdgeEvents(s.edgeId(), s.events())).toList());
        List<UUID> removed = new ArrayList<>();
        plan.merges().forEach(m -> removed.add(m.edgeId()));
        plan.selfLoops().forEach(s -> removed.add(s.edgeId()));
        cleanup.deleteEdges(workId, removed);
        cleanup.moveEdges(workId, plan.moves().stream()
                .map(m -> new EdgeEndpoints(m.edgeId(), m.sourceNodeId(), m.targetNodeId(), m.canonicalKey()))
                .toList(), now);
        entityManager.clear();
    }

    /** 같은 트랜잭션에서 다시 읽은 행이 계약을 지키고 다시 계획해도 할 일이 없어야 한다. 어긋나면 전부 되돌린다. */
    private Totals verify(UUID workId, int cutoff, Map<String, List<UUID>> confirmed,
            List<RelationshipNameRules.ProfileName> profiles, Function<String, String> hasher, Totals expected) {
        List<NodeRow> nodeRows = nodes.findAllByWorkIdOrderByFirstEpisodeNoAscIdAsc(workId).stream()
                .map(RelationshipGraphCleanupServiceImpl::row).toList();
        List<EdgeRow> edgeRows = edges.findAllByWorkId(workId).stream()
                .map(RelationshipGraphCleanupServiceImpl::row).toList();
        List<EventSlot> slots = slots(workId);
        Map<String, Integer> violations = new LinkedHashMap<>(
                RelationshipGraphCleanupRules.verify(nodeRows, edgeRows, slots, hasher));
        Totals after = totals(nodeRows, edgeRows.size(), slots.size());
        if (!after.equals(expected)) violations.put("ROW_COUNTS", 1);
        if (violations.isEmpty()
                && !plan(new Input(cutoff, nodeRows, edgeRows, slots, confirmed, profiles, hasher)).isEmpty())
            violations.put("NOT_IDEMPOTENT", 1);
        if (!violations.isEmpty()) {
            log.error("GH391 relationship graph cleanup verification failed workId={} violations={}",
                    workId, violations);
            throw new AppException(RELATIONSHIP_GRAPH_CONFLICT,
                    "관계도 정리 사후 검증 실패(" + String.join(",", violations.keySet()) + ").");
        }
        return after;
    }

    private List<EventSlot> slots(UUID workId) {
        return events.findSlotRowsByWorkId(workId).stream()
                .map(e -> new EventSlot(e.getId(), e.getEdgeId(), e.getAnalysisId(), e.getEpisodeNo(),
                        e.getDirection()))
                .toList();
    }

    private static Totals totals(List<NodeRow> nodeRows, int edgeCount, int eventCount) {
        int canonical = (int) nodeRows.stream().filter(n -> n.mergedIntoNodeId() == null).count();
        return new Totals(nodeRows.size(), canonical, edgeCount, eventCount);
    }

    private RelationshipGraphCleanupResponse report(UUID workId, boolean apply, boolean applied, int cutoff,
            boolean ordered, List<String> blockers, Plan plan, Totals before, Totals after, List<NodeRow> nodeRows,
            List<RelationshipNameRules.ProfileName> profiles) {
        Map<UUID, String> profileNames = new HashMap<>();
        profiles.forEach(p -> profileNames.putIfAbsent(p.characterId(), p.name()));
        if (plan.nodes().stream().anyMatch(a -> a.profileId() != null && !profileNames.containsKey(a.profileId())))
            for (WorkCharacterNameRow row : characters.findNamesByWorkIdAndStatus(workId, CharacterStatus.ARCHIVED))
                profileNames.putIfAbsent(row.getId(), row.getName());
        Map<UUID, String> nodeNames = nodeRows.stream().collect(Collectors.toMap(NodeRow::id, NodeRow::name));
        var nodeReport = plan.nodes().stream()
                .map(a -> new RelationshipGraphCleanupResponse.NodeRow(
                        a.nodeId(), a.name(), a.phase().name(), a.kind().name(), a.profileId(),
                        a.profileId() == null ? null : profileNames.get(a.profileId()), a.targetNodeId(),
                        a.targetNodeId() == null ? null : nodeNames.get(a.targetNodeId()),
                        a.reason() == null ? null : a.reason().name(),
                        a.resolvedProfileIds().stream()
                                .map(id -> new ResolvedProfile(id, profileNames.get(id))).toList()))
                .toList();
        var counts = new Counts(
                (int) plan.count(NodeActionKind.LINK),
                (int) plan.count(NodeActionKind.MERGE_UNLINKED),
                (int) plan.count(NodeActionKind.MERGE_LINKED),
                (int) plan.count(NodeActionKind.HELD),
                (int) plan.count(NodeActionKind.SKIPPED),
                plan.moves().size(),
                plan.merges().size(),
                plan.selfLoops().size(),
                plan.movedEvents(),
                plan.swappedEvents(),
                plan.evidenceMerges().size(),
                plan.selfLoopEvents());
        return new RelationshipGraphCleanupResponse(
                workId, apply, applied, cutoff, ordered, List.copyOf(blockers), counts, before, after, nodeReport,
                plan.groups().stream()
                        .map(g -> new EdgeGroupRow(g.survivorId(), g.mergedEdgeIds(), g.moved(), g.suppressed(),
                                g.corrected(), g.correctionConflict()))
                        .toList(),
                plan.selfLoops().stream().map(RelationshipGraphCleanupRules.SelfLoop::edgeId).toList(),
                plan.warnings().stream().map(w -> new WarningRow(w.code(), w.survivorId(), w.edgeId())).toList());
    }

    /** 반영된 관계 분석이 없는 작품은 정리할 기준 회차가 없어 아무것도 읽거나 쓰지 않는다. */
    private static RelationshipGraphCleanupResponse empty(UUID workId, boolean apply, List<String> blockers) {
        return new RelationshipGraphCleanupResponse(workId, apply, false, null, false, List.copyOf(blockers),
                new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null, null, List.of(), List.of(), List.of(),
                List.of());
    }

    private static NodeRow row(RelationshipGraphNode n) {
        return new NodeRow(n.getId(), n.getName(), n.getCharacterId(), n.getCharacterLinkedEpisodeNo(),
                n.getFirstEpisodeNo(), n.getMergedIntoNodeId(), n.getCreatedAt());
    }

    private static EdgeRow row(RelationshipGraphEdge e) {
        return new EdgeRow(e.getId(), e.getSourceNodeId(), e.getTargetNodeId(), e.getAxis(), e.getKind(),
                e.getCanonicalKey(), e.isSuppressed(), e.getCorrectedSummary(), e.getVersion(), e.getCreatedAt());
    }
}
