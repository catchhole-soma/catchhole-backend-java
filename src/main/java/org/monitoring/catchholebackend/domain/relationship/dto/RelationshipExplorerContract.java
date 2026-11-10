package org.monitoring.catchholebackend.domain.relationship.dto;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Bounded read contract; existing graph/write APIs remain compatible. */
public final class RelationshipExplorerContract {
    private RelationshipExplorerContract() {}
    public record Person(Node node, long neighborCount) {}
    @Schema(description = "관계 300개 또는 인물 100명 이상은 인물 선택부터 시작합니다. 중심 인물의 직접 관계는 100개씩 조회합니다.")
    public record Exploration(Graph graph, boolean selectionRequired, long totalNodeCount,
            List<Person> suggestedPeople, Node focusNode, int page, int pageSize, long scopeEdgeCount,
            boolean hasNext) {}
}
