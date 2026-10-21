package org.monitoring.catchholebackend.domain.relationship.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import java.util.List;
import org.monitoring.catchholebackend.domain.relationship.type.FamilyTreeNodeType;
import org.monitoring.catchholebackend.domain.worldimage.dto.response.WorldSettingImageResponse;

@Schema(description = "가계도의 활성 캐릭터 또는 현재 설정에 이름만 명시된 인물. 설정과 원문은 상세에서 조회합니다")
public record FamilyTreeNodeResponse(
        @Schema(description = "도형 ID. 캐릭터는 UUID 문자열, 미등록 인물은 mention:<후보 UUID>, 명시 묶음은 mention-group:<그룹 UUID>") String id,
        @Schema(description = "현재 인물 이름. 원래 이름을 자르지 않습니다") String name,
        @Schema(description = "CHARACTER는 프로필, MENTION은 해당 후보의 설정 근거를 엽니다") FamilyTreeNodeType nodeType,
        @Schema(description = "등록된 캐릭터 ID. 미등록 인물은 null", nullable = true) UUID characterId,
        @Schema(description = "개별 미등록 인물의 후보 ID. 등록 캐릭터와 명시 묶음은 null", nullable = true) UUID candidateId,
        @Schema(description = "등록 캐릭터의 현재 프로필 이미지. 기본 이미지는 경로가 없고 미등록 인물은 null", nullable = true) WorldSettingImageResponse image,
        @Schema(description = "사용자가 명시적으로 묶은 미등록 인물 그룹 ID", nullable = true) UUID mentionGroupId,
        @Schema(description = "묶음 조회 당시 버전. 해제 요청에 사용합니다", nullable = true) Long mentionGroupVersion,
        @Schema(description = "현재 표시된 이 미등록 인물의 근거 후보 ID들. 등록 캐릭터는 빈 목록") List<UUID> candidateIds
) {}
