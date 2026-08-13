package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "같은 캐릭터 이름으로 묶은 설정 후보 그룹")
public record SettingCandidateGroupResponse(
        @Schema(description = "정규화된 캐릭터 이름 기반 그룹 식별자", example = "아리아")
        String groupKey,

        @Schema(description = "화면에 표시할 캐릭터 이름", example = "아리아")
        String entityName,

        @Schema(description = "그룹에 포함된 후보 수", example = "3")
        int candidateCount,

        @Schema(description = "그룹 후보의 원문 근거 회차 목록")
        List<Integer> evidenceEpisodeNos,

        @Schema(description = "그룹에 포함된 설정 후보. 회차·생성 순으로 정렬됩니다.")
        List<SettingCandidateResponse> candidates
) {
}
