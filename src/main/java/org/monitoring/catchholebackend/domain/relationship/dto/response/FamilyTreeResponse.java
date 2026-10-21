package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "동일한 DB 스냅샷에서 읽은 가계도 요약. 합계는 시점·초점 범위를 적용한 뒤 표시 상한을 적용하기 전의 수입니다")
public record FamilyTreeResponse(
        @Schema(description = "표시 간선의 양 끝 인물. 등록 캐릭터와 명시적 미등록 인물을 구분하며 초점은 항상 포함합니다") List<FamilyTreeNodeResponse> nodes,
        @Schema(description = "VALID 관계와 현재 설정으로 뒷받침된 명시적 언급. 합계 최대 limit개입니다") List<FamilyTreeEdgeResponse> edges,
        @Schema(description = "요청 범위의 전체 인물 수", example = "12") int totalNodeCount,
        @Schema(description = "요청 범위의 전체 유효 관계 수", example = "14") int totalEdgeCount,
        @Schema(description = "작품 전체 PENDING 후보 수. 초점·시점·표시 상한에 영향받지 않습니다", example = "2") long pendingCandidateCount,
        @Schema(description = "상한 때문에 일부 관계가 생략됐는지 여부") boolean truncated,
        @Schema(description = "간선 표시 상한. 1~200, 기본 100", example = "100") int limit,
        @Schema(description = "유효한 과거 관계 포함 여부") boolean includeHistorical,
        @Schema(description = "연결된 가족 범위의 기준 인물. 미지정이면 작품 전체", nullable = true) UUID focusCharacterId,
        @Schema(description = "범위·설정 세대·인물·유효 관계·언급·후보 수의 SHA-256 변경 감지값. 권한이나 쓰기 버전이 아닙니다") String revision,
        @Schema(description = "작품 전체에서 미등록 인물로 표시 가능한 PENDING 후보 수. 연결은 선택 사항이며 초점·시점·상한과 무관합니다") long optionalLinkCount
) {}
