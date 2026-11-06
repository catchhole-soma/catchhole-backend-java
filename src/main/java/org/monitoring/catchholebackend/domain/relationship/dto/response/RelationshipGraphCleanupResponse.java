package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "관계도 1회 정리(GH391) 보고서. 노드·프로필 이름만 담고 관계 설명·원문 근거는 담지 않습니다")
public record RelationshipGraphCleanupResponse(
        @Schema(description = "작품 ID") UUID workId,
        @Schema(description = "적용을 요청했는지(false는 dry-run)") boolean apply,
        @Schema(description = "이번 요청에서 실제로 바꾼 것이 있는지") boolean applied,
        @Schema(description = "정리 기준 회차(공개 세대의 마지막 반영 회차). 반영된 관계 분석이 없으면 null", nullable = true)
        Integer cutoffEpisodeNo,
        @Schema(description = "ordered 작품이라 ACTIVE 대표 이름을 확정 근거로 썼는지") boolean orderedAnchors,
        @Schema(description = "적용을 막는 사유. ANALYSIS_IN_PROGRESS, CONTEXT_FROZEN, BACKFILL_ACTIVE") List<String> blockers,
        @Schema(description = "건수") Counts counts,
        @Schema(description = "정리 전 행 수. 기준 회차가 없으면 null", nullable = true) Totals before,
        @Schema(description = "정리 뒤 행 수. dry-run은 계획대로 적용했을 때의 예상, 기준 회차가 없으면 null", nullable = true) Totals after,
        @Schema(description = "노드별 결정. 결정 대상이 아닌 노드는 없습니다") List<NodeRow> nodes,
        @Schema(description = "같은 slot이 되어 하나로 합친 선 묶음") List<EdgeGroupRow> edgeGroups,
        @Schema(description = "자기 자신을 잇게 되어 지우는 선") List<UUID> selfLoopEdgeIds,
        @Schema(description = "검토가 필요한 경고. CORRECTION_CONFLICT는 두 선의 정정이 달라 생존 선의 것을 유지한 경우입니다")
        List<WarningRow> warnings
) {
    @Schema(name = "RelationshipGraphCleanupCounts", description = "정리 건수")
    public record Counts(
            @Schema(description = "미연결 노드를 프로필에 연결") int nodesLinked,
            @Schema(description = "미연결 노드를 프로필 노드로 병합") int nodesMergedUnlinked,
            @Schema(description = "같은 프로필 연결 노드를 대표 노드로 병합") int nodesMergedLinked,
            @Schema(description = "여러 프로필로 해소되어 보류") int nodesHeld,
            @Schema(description = "사유를 남기고 건너뜀(보관 프로필 연결 보고 포함)") int nodesSkipped,
            @Schema(description = "끝점을 옮긴 생존 선") int edgesMoved,
            @Schema(description = "생존 선으로 합쳐 지운 선") int edgesMerged,
            @Schema(description = "자기 연결이 되어 지운 선") int edgesDeleted,
            @Schema(description = "생존 선으로 옮긴 event") int eventsMoved,
            @Schema(description = "옮기며 source/target 이름을 맞바꾼 event") int eventsNamesSwapped,
            @Schema(description = "같은 분석 event에 근거를 합치고 지운 event") int eventsEvidenceMerged,
            @Schema(description = "자기 연결 선과 함께 지운 event") int eventsDeleted
    ) {}

    @Schema(name = "RelationshipGraphCleanupTotals", description = "행 수")
    public record Totals(
            @Schema(description = "노드 행(병합 노드 포함)") int nodes,
            @Schema(description = "병합되지 않은 노드") int canonicalNodes,
            @Schema(description = "선") int edges,
            @Schema(description = "event") int events
    ) {}

    @Schema(name = "RelationshipGraphCleanupNode", description = "노드별 결정")
    public record NodeRow(
            @Schema(description = "노드 ID") UUID nodeId,
            @Schema(description = "저장된 관측 이름") String name,
            @Schema(description = "LINKED(같은 프로필 연결 노드) 또는 UNLINKED(미연결 노드)") String phase,
            @Schema(description = "LINK, MERGE_UNLINKED, MERGE_LINKED, HELD, SKIPPED") String action,
            @Schema(description = "연결(된) 프로필 ID", nullable = true) UUID profileId,
            @Schema(description = "프로필 대표 이름", nullable = true) String profileName,
            @Schema(description = "합칠 대표 노드 ID", nullable = true) UUID targetNodeId,
            @Schema(description = "합칠 대표 노드의 저장 이름", nullable = true) String targetNodeName,
            @Schema(description = "보류·건너뜀 사유", nullable = true) String reason,
            @Schema(description = "보류·다른 프로필일 때 이름이 해소된 프로필들") List<ResolvedProfile> resolvedProfiles
    ) {}

    @Schema(name = "RelationshipGraphCleanupResolvedProfile", description = "이름이 해소된 프로필")
    public record ResolvedProfile(UUID profileId, String profileName) {}

    @Schema(name = "RelationshipGraphCleanupEdgeGroup", description = "하나로 합친 선 묶음")
    public record EdgeGroupRow(
            @Schema(description = "남는 선 ID") UUID survivorEdgeId,
            @Schema(description = "합쳐 지우는 선 ID") List<UUID> mergedEdgeIds,
            @Schema(description = "남는 선도 끝점을 옮기는지") boolean moved,
            @Schema(description = "합친 뒤 삭제 억제 상태") boolean suppressed,
            @Schema(description = "합친 뒤 정정 설명이 있는지") boolean corrected,
            @Schema(description = "두 선 이상에 서로 다른 정정이 있었는지") boolean correctionConflict
    ) {}

    @Schema(name = "RelationshipGraphCleanupWarning", description = "검토가 필요한 경고")
    public record WarningRow(String code, UUID survivorEdgeId, UUID edgeId) {}
}
