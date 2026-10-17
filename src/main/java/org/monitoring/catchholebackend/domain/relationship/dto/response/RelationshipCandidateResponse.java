package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipAssertion;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateStatus;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipKind;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipCandidateOrigin;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipSubtype;
import org.monitoring.catchholebackend.domain.relationship.type.RelationshipTemporalScope;

@Schema(description = "가족 관계 후보와 검토 보류 사유")
public record RelationshipCandidateResponse(
        @Schema(description = "검토 후보 ID") UUID id,
        @Schema(description = "현재 검토 버전") long version,
        @Schema(description = "후보 검토 상태") RelationshipCandidateStatus status,
        @Schema(description = "서술 주체 캐릭터 ID") UUID subjectCharacterId,
        @Schema(description = "상대 캐릭터 ID. 미해결이면 null") UUID objectCharacterId,
        @Schema(description = "미해결 상대 호칭") String objectMention,
        @Schema(description = "정규화 전 관계 방향과 종류") RelationshipKind kind,
        @Schema(description = "명시된 세부 관계") RelationshipSubtype subtype,
        @Schema(description = "작품 내 관계 시점") RelationshipTemporalScope temporalScope,
        @Schema(description = "명시된 시점 또는 기간 설명") String timeLabel,
        @Schema(description = "단정·불확실·부정·비유·모순 구분") RelationshipAssertion assertion,
        @Schema(description = "확정된 관계 ID. 미확정이면 null") UUID confirmedRelationshipId,
        @Schema(description = "현재 확인된 보류 사유. 순환 등 확정 직전 검사는 별도로 수행합니다") List<String> reviewReasons,
        List<RelationshipEvidenceResponse> evidence,
        @Schema(description = "시스템에 발견/등록된 시각. 관계 성립 시점이 아닙니다") LocalDateTime createdAt,
        @Schema(description = "후보 생성 경로") RelationshipCandidateOrigin origin,
        @Schema(description = "현재 설정에서 자동으로 확정했는지") boolean automaticallyConfirmed,
        @Schema(description = "주체의 현재 이름") String subjectCharacterName,
        @Schema(description = "상대의 현재 이름. 미해결이면 null") String objectCharacterName
) {}
