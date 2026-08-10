package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "세계관 설정 후보 작가 수정안 저장 결과")
public record WorldSettingCandidateDecisionUpdateResponse(
        String groupKey,
        List<WorldSettingCandidateResponse> candidates
) {
}
