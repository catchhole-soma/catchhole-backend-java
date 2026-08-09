package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCandidateGroupStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonScope;

@Schema(description = "분류와 대상이 같은 세계관 후보 key 묶음")
public record WorldSettingCandidateGroupResponse(
        String groupKey,
        WorldSettingCategory category,
        String subjectName,
        int changeCount,
        int addCount,
        int updateCount,
        int mergeCount,
        int excludeCount,
        List<Integer> evidenceEpisodeNos,
        WorldSettingCandidateGroupStatus status,
        @Schema(nullable = true) WorldSettingRecomparisonScope recomparisonScope,
        List<WorldSettingCandidateResponse> candidates
) {
}
