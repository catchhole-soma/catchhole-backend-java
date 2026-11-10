package org.monitoring.catchholebackend.domain.relationship.dto;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Bounded read contract; existing graph/write APIs remain compatible. */
public final class RelationshipExplorerContract {
    private RelationshipExplorerContract() {}
    @Schema(description = "관계가 있는 인물의 목록 요약. 관계 수는 종료 관계와 같은 상대의 복수 관계를 각각 포함합니다.")
    public record Person(Node node, long neighborCount,
            @Schema(description = "전체 목록에서는 인물의 전체 관계 수, 상대 목록에서는 중심 인물과의 관계 수") long edgeCount) {}
    @Schema(description = "관계 300개 또는 인물 100명 이상은 인물 선택부터 시작합니다. 중심 인물의 직접 관계는 100개씩 조회합니다.")
    public record Exploration(Graph graph, boolean selectionRequired, long totalNodeCount,
            List<Person> suggestedPeople, Node focusNode, int page, int pageSize, long scopeEdgeCount,
            boolean hasNext) {}

    @Schema(description = "중심 관계가 200개 이상이면 상대를 선택합니다. 최대 20명·200개 관계를 함께 조회하며 단독 상대의 200개 초과 관계만 페이지로 조회합니다.")
    public record SelectionExploration(Graph graph, boolean selectionRequired, long totalNodeCount,
            Node focusNode, long focusEdgeCount, boolean counterpartSelectionRequired,
            List<Person> selectedPeople, int page, int pageSize, long scopeEdgeCount, boolean hasNext,
            int maxSelectedPeople, int maxSelectedEdges) {}
}
