package org.monitoring.catchholebackend.domain.relationship.mapper;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Context;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Decision;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Edge;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.EdgeStatus;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Node;
import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.Operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEdge;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphNode;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class RelationshipGraphMapper {
    private final ObjectMapper json = new ObjectMapper();

    public JsonNode toJson(Object value) {
        return json.valueToTree(value);
    }

    public <T> T fromJson(JsonNode value, Class<T> type) {
        return json.convertValue(value, type);
    }

    public List<Decision> decisions(RelationshipGraphAnalysis analysis) {
        if (analysis.getDecisions() == null) return List.of();
        return Arrays.asList(fromJson(analysis.getDecisions(), Decision[].class));
    }

    public Context toContext(RelationshipGraphAnalysis analysis) {
        Context source =
                analysis.getContext() == null
                        ? new Context(
                                1,
                                analysis.getSourceHash(),
                                analysis.getContextToken(),
                                analysis.getStatus(),
                                analysis.getEpisodeNo(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of())
                        : fromJson(analysis.getContext(), Context.class);
        return new Context(
                1,
                source.sourceHash(),
                source.contextToken(),
                analysis.getStatus(),
                source.sourceEpisodeNo(),
                source.observations(),
                source.nodes(),
                source.relationships(),
                source.heldObservations());
    }

    public Node toNode(RelationshipGraphNode node, String name, int cutoff) {
        return new Node(
                node.getId(),
                name,
                null,
                node.getCharacterId() != null
                        && node.getCharacterLinkedEpisodeNo() != null
                        && node.getCharacterLinkedEpisodeNo() <= cutoff);
    }

    public Edge toEdge(
            RelationshipGraphEdge edge, RelationshipGraphEvent event, int evidenceCount) {
        return new Edge(
                edge.getId(),
                edge.getSourceNodeId(),
                edge.getTargetNodeId(),
                edge.getAxis(),
                edge.getKind(),
                event.getLabel(),
                edge.getCorrectedSummary() == null
                        ? event.getSummary()
                        : edge.getCorrectedSummary(),
                event.getDirection(),
                event.getStatus(),
                edge.getVersion(),
                evidenceCount);
    }

    public RelationshipGraphEvent toEvent(
            RelationshipGraphAnalysis analysis,
            RelationshipGraphEdge edge,
            Decision decision,
            String sourceName,
            String targetName,
            JsonNode evidence) {
        return RelationshipGraphEvent.create(
                analysis.getWorkId(),
                analysis.getId(),
                edge.getId(),
                analysis.getEpisodeId(),
                analysis.getEpisodeNo(),
                sourceName,
                targetName,
                decision.label(),
                decision.summary(),
                decision.direction(),
                decision.operation() == Operation.END ? EdgeStatus.ENDED : EdgeStatus.ACTIVE,
                evidence);
    }
}
