package org.monitoring.catchholebackend.domain.relationship.mapper;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.dto.RelationshipExplorerContract.*;
import org.monitoring.catchholebackend.domain.relationship.repository.RelationshipExplorerRepository;
import org.monitoring.catchholebackend.global.common.response.PageResponse;
import org.springframework.stereotype.Component;

@Component
public class RelationshipExplorerMapper {
    public Person toPersonResponse(RelationshipExplorerRepository.Person person) {
        return new Person(person.node(), person.neighborCount(), person.edgeCount());
    }

    public PageResponse<Person> toPeopleResponse(RelationshipExplorerRepository.PeoplePage people, int page, int size) {
        return new PageResponse<>(people.content().stream().map(this::toPersonResponse).toList(), page, size,
                people.total(), (int) ((people.total() + size - 1) / size), (long) (page + 1) * size < people.total());
    }

    public Graph toGraphResponse(EpisodeRef selected, List<EpisodeRef> available, String revision, List<Node> nodes,
            List<Edge> edges, boolean truncated, long total, String status, boolean partial, boolean incomplete,
            List<Integer> incompleteEpisodes) {
        return new Graph(selected, available, revision, nodes, edges, truncated, total, status, partial, incomplete, incompleteEpisodes);
    }

    public Exploration toExplorationResponse(Graph graph, boolean selection, long people, List<Person> suggested,
            Node focus, int page, int size, long scope, boolean next) {
        return new Exploration(graph, selection, people, suggested, focus, page, size, scope, next);
    }

    public SelectionExploration toSelectionExplorationResponse(Graph graph, boolean selection, long people,
            Node focus, long focusEdges, boolean counterpartsRequired, List<RelationshipExplorerRepository.Person> selected,
            int page, int size, long scope, boolean next, int maxPeople, int maxEdges) {
        return new SelectionExploration(graph, selection, people, focus, focusEdges, counterpartsRequired,
                selected.stream().map(this::toPersonResponse).toList(), page, size, scope, next, maxPeople, maxEdges);
    }

    public Edge toEdgeResponse(UUID id, UUID source, UUID target, Axis axis, Kind kind, String label, String summary,
            Direction direction, EdgeStatus status, long version, int evidenceCount, int verified, boolean incomplete) {
        return new Edge(id, source, target, axis, kind, label, summary, direction, status, version, evidenceCount, verified, incomplete);
    }

    public Detail toDetailResponse(Edge edge, Node source, Node target, List<PublicEvidence> evidence, long version) {
        return new Detail(edge, source, target, evidence, version);
    }
}
