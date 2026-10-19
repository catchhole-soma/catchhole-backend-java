package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;


@Schema(description = "관계 근거와 현재 유효성. 인용문은 기존 Fact 근거 API로 조회합니다")
public record RelationshipEvidenceResponse(
        @Schema(description = "이 근거를 확인한 검토 후보 ID") UUID candidateId,
        @Schema(description = "근거 Fact ID") UUID sourceFactId,
        @Schema(description = "근거 프로필 소유 캐릭터 ID") UUID characterId,
        @Schema(description = "근거 설정 key") String factKey,
        @Schema(description = "후보 생성 당시 슬롯 토큰") String sourceToken,
        @Schema(description = "현재 슬롯과 토큰이 일치하는지") boolean current,
        @Schema(description = "현재 슬롯 참여 여부와 별개인 원문 인용 존재 여부") boolean hasEvidence,
        @Schema(description = "직접 수정한 설정 근거인지") boolean manuallyEdited,
        @Schema(description = "원문 근거 조회 경로. 원문이 없으면 null") String evidencePath,
        @Schema(description = "현재 설정값. 현재 근거일 때만 제공하고 과거 원문으로 대신하지 않습니다") Object currentValue
) {}
