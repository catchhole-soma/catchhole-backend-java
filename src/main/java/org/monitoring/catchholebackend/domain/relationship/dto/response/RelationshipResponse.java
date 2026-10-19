package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipValidity;

@Schema(description = "정규화한 가족 관계. 현재성 검증과 서사상의 시점을 분리합니다")
public record RelationshipResponse(
        @Schema(description = "확정 관계 ID") UUID id,
        @Schema(description = "근거 추가 또는 철회 때 증가하는 관계 버전") long version,
        @Schema(description = "정규화한 주체 ID. 부모 관계라면 부모") UUID subjectCharacterId,
        @Schema(description = "정규화한 상대 ID. 부모 관계라면 자녀") UUID objectCharacterId,
        @Schema(description = "정규화한 관계 유형") RelationshipKind kind,
        @Schema(description = "명시된 세부 관계") RelationshipSubtype subtype,
        @Schema(description = "작품 내 현재·과거 관계 구분") RelationshipTemporalScope temporalScope,
        @Schema(description = "명시된 관계 성립 시점 또는 기간") String timeLabel,
        @Schema(description = "현재 근거 유효성. VALID만 가계도에서 사용할 수 있습니다") RelationshipValidity validity,
        @Schema(description = "가계도 기본 표시 대상: VALID이면서 CURRENT") boolean visibleInFamilyTree,
        List<RelationshipEvidenceResponse> evidence,
        @Schema(description = "등록 시각. 관계 성립 시점이 아닙니다") LocalDateTime createdAt,
        @Schema(description = "주체의 현재 이름") String subjectCharacterName,
        @Schema(description = "상대의 현재 이름") String objectCharacterName
) {}
