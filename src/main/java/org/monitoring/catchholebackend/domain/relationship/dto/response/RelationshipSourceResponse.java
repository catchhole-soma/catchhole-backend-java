package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;


@Schema(description = "현재 프로필의 관계 생성 입력. 과거 Fact는 포함하지 않습니다")
public record RelationshipSourceResponse(
        @Schema(description = "현재 프로필 Fact ID") UUID factId,
        @Schema(description = "프로필 소유 캐릭터 ID") UUID characterId,
        @Schema(description = "현재 프로필 슬롯 key") String factKey,
        @Schema(description = "현재 슬롯 전체 표시값과 구조화 값") Object currentValue,
        @Schema(description = "현재 슬롯 값과 출처 목록에 결속된 토큰") String contextToken,
        @Schema(description = "조회 가능한 원문 인용이 존재하는지") boolean hasEvidence,
        @Schema(description = "직접 수정한 현재 슬롯인지") boolean manuallyEdited
) {}
