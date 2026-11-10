package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

@Schema(description = "가족 관계가 200개를 넘으면 가계도 대신 전체 유효 관계의 등록 인물 선택 목록을 반환합니다")
public record FamilyTreeExplorationResponse(
        boolean selectionRequired,
        int totalNodeCount,
        int totalEdgeCount,
        @Schema(description = "선택이 필요하면 null. 인물을 지정하면 연결된 가족 관계를 최대 200개 반환") FamilyTreeResponse graph,
        @Schema(description = "선택이 필요할 때만 반환하는 전체 범위의 등록 인물 페이지") PageResponse<Person> people) {
    @Schema(name = "FamilyTreePerson")
    public record Person(UUID id, String name,
            @Schema(description = "현재 조회 시점의 서로 다른 직접 연결 가족 인원. 동일 상대의 복수 관계는 한 번만 계산") int familyCount) {}
}
