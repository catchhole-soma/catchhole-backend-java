package org.monitoring.catchholebackend.domain.relationship.service;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;
import static org.monitoring.catchholebackend.domain.relationship.exception.RelationshipGraphErrorCode.*;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.*;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipExplorerRepository;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipExplorerRepository.Analysis;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipExplorerRepository.Row;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.relationship.mapper.RelationshipExplorerMapper;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RelationshipExplorerServiceImpl implements RelationshipExplorerService {
    public static final int OVERVIEW_THRESHOLD = 300;
    public static final int OVERVIEW_NODE_THRESHOLD = 100;
    public static final int EDGE_PAGE_SIZE = 100;
    private final WorkRepository works;
    private final RelationshipExplorerRepository reads;
    private final RelationshipExplorerMapper mapper;

    public Exploration exploreRelationships(Long member, UUID work, Integer episodeNo, UUID focus, int page) {
        if (page < 0 || page > 10000 || focus == null && page != 0) invalid();
        Snapshot s = snapshot(member, work, episodeNo);
        var stats = reads.stats(work, s.cutoff());
        if (!stats.integrity()) conflict();
        Node focusNode = focus == null ? null : reads.nodes(work, s.cutoff(), List.of(focus)).stream()
                .map(RelationshipExplorerRepository.Person::node).findFirst().orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_NOT_FOUND));
        boolean selection = focus == null && (stats.edgeCount() >= OVERVIEW_THRESHOLD || stats.peopleCount() >= OVERVIEW_NODE_THRESHOLD);
        long scope = focus == null ? stats.edgeCount() : reads.scopeCount(work, s.cutoff(), focus);
        int size = focus == null ? OVERVIEW_THRESHOLD - 1 : EDGE_PAGE_SIZE;
        var rows = selection ? List.<Row>of() : reads.edges(work, s.cutoff(), focus, null, page * size, size);
        List<Edge> edges = edges(work, s, rows);
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> { ids.add(r.source()); ids.add(r.target()); });
        if (focus != null) ids.add(focus);
        List<Node> nodes = reads.nodes(work, s.cutoff(), ids).stream().map(RelationshipExplorerRepository.Person::node).toList();
        List<Person> suggested = selection ? reads.people(work, s.cutoff(), "", 0, 8).stream()
                .map(mapper::toPersonResponse).toList() : List.of();
        boolean next = !selection && (long)(page + 1) * size < scope;
        // No stale graph cache: revision covers returned data and status, not internal JSON.
        String revision = Integer.toHexString(Objects.hash(edges, nodes, s.available(), s.status(), s.incomplete(), stats.edgeCount()));
        Graph graph = mapper.toGraphResponse(s.selected(), s.available(), revision, nodes, edges, false,
                stats.edgeCount(), s.status(), s.partial(), !s.incomplete().isEmpty(), s.incomplete());
        return mapper.toExplorationResponse(graph, selection, stats.peopleCount(), suggested, focusNode, page, size, scope, next);
    }

    public PageResponse<Person> searchRelationshipPeople(Long member, UUID work, Integer episodeNo, String query, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 50 || query == null || query.length() > 100) invalid();
        Snapshot s = snapshot(member, work, episodeNo);
        if (!reads.stats(work, s.cutoff()).integrity()) conflict();
        var found = reads.peoplePage(work, s.cutoff(), query.strip(), page * size, size);
        return mapper.toPeopleResponse(found, page, size);
    }

    public Detail getRelationshipDetail(Long member, UUID work, UUID edge, Integer episodeNo) {
        Snapshot s = snapshot(member, work, episodeNo);
        if (!reads.stats(work, s.cutoff()).integrity()) conflict();
        var rows = reads.edges(work, s.cutoff(), null, edge, 0, 1);
        if (rows.isEmpty()) throw new AppException(RELATIONSHIP_GRAPH_NOT_FOUND);
        Row row = rows.getFirst();
        var nodes = reads.nodes(work, s.cutoff(), List.of(row.source(), row.target()));
        Map<UUID, Node> byId = new HashMap<>();
        nodes.forEach(p -> byId.put(p.node().id(), p.node()));
        return mapper.toDetailResponse(edges(work, s, rows).getFirst(), byId.get(row.source()), byId.get(row.target()),
                reads.evidence(work, s.cutoff(), edge), row.version());
    }

    private List<Edge> edges(UUID work, Snapshot s, List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        var counts = reads.evidenceCounts(work, s.cutoff(), rows.stream().map(Row::id).toList());
        Map<Set<UUID>, Integer> failures = new HashMap<>();
        for (var failure : reads.failures(work, s.verificationCutoff())) {
            if (!failure.valid()) conflict();
            failures.merge(new HashSet<>(List.of(failure.source(), failure.target())), failure.episodeNo(), Math::min);
        }
        return rows.stream().map(r -> {
            Integer failedAt = failures.get(Set.of(r.source(), r.target()));
            int verified = 0;
            boolean incomplete = false, advancing = true;
            for (Analysis a : s.considered()) {
                boolean gap = a.status().equals("FAILED") || a.status().equals("INVALIDATED")
                        || a.status().equals("APPLIED") && (!a.valid() || a.rejected() > 0)
                        || failedAt != null && a.episode().episodeNo() >= failedAt;
                if (gap) { incomplete = true; advancing = false; }
                if (!a.status().equals("APPLIED")) advancing = false;
                if (advancing) verified = a.episode().episodeNo();
            }
            return mapper.toEdgeResponse(r.id(), r.source(), r.target(), r.axis(), r.kind(), r.label(), r.summary(),
                    r.direction(), r.status(), r.version(), counts.getOrDefault(r.id(), 0), verified, incomplete);
        }).toList();
    }

    private Snapshot snapshot(Long member, UUID work, Integer episodeNo) {
        works.getOwnedWork(work, member);
        if (episodeNo != null && episodeNo < 1) invalid();
        int verification = episodeNo == null ? Integer.MAX_VALUE : episodeNo;
        var all = reads.analyses(work, verification);
        var available = all.stream().filter(Analysis::valid).map(Analysis::episode).toList();
        EpisodeRef selected = episodeNo == null ? (available.isEmpty() ? null : available.getLast())
                : available.stream().filter(e -> e.episodeNo() == episodeNo).findFirst()
                        .orElseThrow(() -> new AppException(RELATIONSHIP_GRAPH_NOT_FOUND));
        var considered = all.stream().filter(a -> a.episode().episodeNo() <= verification).toList();
        String status = "NOT_ANALYZED";
        boolean partial = considered.stream().anyMatch(a -> !a.status().equals("APPLIED") || !a.valid()
                || a.rejected() > 0 || a.failures() || a.openHold());
        if (!considered.isEmpty()) {
            Analysis last = considered.getLast();
            status = switch (last.status()) {
                case "PENDING", "PREPARED", "COMPLETED" -> "PROCESSING";
                case "APPLIED" -> last.valid() ? (partial ? "PARTIAL" : "COMPLETED") : "INVALIDATED";
                default -> last.status();
            };
        }
        var incomplete = considered.stream().filter(a -> a.status().equals("FAILED") || a.status().equals("INVALIDATED")
                || a.status().equals("APPLIED") && (!a.valid() || a.rejected() > 0 || a.failures()))
                .map(a -> a.episode().episodeNo()).toList();
        return new Snapshot(selected, available, considered, selected == null ? 0 : selected.episodeNo(), verification, status, partial, incomplete);
    }

    private record Snapshot(EpisodeRef selected, List<EpisodeRef> available, List<Analysis> considered,
            int cutoff, int verificationCutoff, String status, boolean partial, List<Integer> incomplete) {}
    private static void invalid() { throw new AppException(RELATIONSHIP_GRAPH_INVALID_INPUT); }
    private static void conflict() { throw new AppException(RELATIONSHIP_GRAPH_CONFLICT); }
}
