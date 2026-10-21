package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "사용자가 묶은 미등록 인물. 각 후보의 관계·근거와 검토 상태는 그대로 유지합니다")
public record RelationshipMentionGroupResponse(
        @Schema(description = "미등록 인물 그룹 ID") UUID id,
        @Schema(description = "그룹 구성 변경 버전. 해제 요청에 사용합니다") long version,
        @Schema(description = "사용자가 지정한 공통 표시 이름") String name,
        @Schema(description = "해제하지 않은 그룹인지 여부") boolean active,
        @Schema(description = "현재 소속 후보 ID. 근거 상세는 기존 후보 조회 API를 사용합니다") List<UUID> candidateIds,
        @Schema(description = "현재 출처 변경·순환·세대 충돌 등 표시 보류 사유") List<String> reviewReasons
) {}
