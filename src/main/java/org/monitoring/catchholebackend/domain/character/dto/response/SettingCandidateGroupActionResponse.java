package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "캐릭터 설정 후보 그룹 일괄 연결 또는 전체 확정 결과")
public record SettingCandidateGroupActionResponse(
        String groupKey,
        String entityName,
        List<SettingCandidateResponse> candidates
) {
}
