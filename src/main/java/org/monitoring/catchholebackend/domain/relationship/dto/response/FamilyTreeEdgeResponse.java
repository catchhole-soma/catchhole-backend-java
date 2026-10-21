package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeEdgeSourceType;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeRole;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@Schema(description = "현재 유효한 확정 관계 또는 명시적 미등록 인물 언급. 출처 유형에 맞는 기존 상세 API를 사용합니다")
public record FamilyTreeEdgeResponse(
        @Schema(description = "도형 ID. 확정 관계 UUID 문자열 또는 mention:<후보 UUID>") String id,
        @Schema(description = "확정 관계 또는 언급 후보의 현재 버전") long version,
        @Schema(description = "주체 캐릭터 ID. 미등록 끝점은 null. PARENT_OF이면 부모", nullable = true) UUID subjectCharacterId,
        @Schema(description = "상대 캐릭터 ID. 미등록 끝점은 null. PARENT_OF이면 자녀", nullable = true) UUID objectCharacterId,
        @Schema(description = "정규화 관계 유형. CHILD_OF와 DESCENDANT_OF는 반환하지 않습니다") RelationshipKind kind,
        @Schema(description = "명시된 세부 관계") RelationshipSubtype subtype,
        @Schema(description = "작품 내 CURRENT 또는 HISTORICAL") RelationshipTemporalScope temporalScope,
        @Schema(description = "명시된 관계 성립 시점 또는 기간", nullable = true) String timeLabel,
        @Schema(description = "기존 관계 상세 또는 후보 상세 중 사용할 출처") FamilyTreeEdgeSourceType sourceType,
        @Schema(description = "확정 관계 상세 ID. MENTION은 null", nullable = true) UUID relationshipId,
        @Schema(description = "언급 근거 후보 ID. RELATIONSHIP은 null", nullable = true) UUID candidateId,
        @Schema(description = "주체 도형 ID. PARENT_OF이면 부모") String subjectNodeId,
        @Schema(description = "상대 도형 ID. PARENT_OF이면 자녀") String objectNodeId,
        @Schema(description = "부모 끝점의 명시된 호칭. 미상·충돌은 PARENT, 부모 관계 이외는 null", nullable = true) FamilyTreeRole subjectRole,
        @Schema(description = "자녀 끝점의 명시된 호칭. 미상·충돌은 CHILD, 부모 관계 이외는 null", nullable = true) FamilyTreeRole objectRole,
        @Schema(description = "명시된 조상·후손 세대 차이. N대손은 N, 미상과 다른 관계는 null", example = "5", nullable = true) Integer generationGap
) {}
