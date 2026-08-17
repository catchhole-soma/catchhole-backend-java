package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "토큰 부족으로 중단된 세계관 비교 일괄 재개 결과")
public record WorldSettingTokenInterruptedResumeResponse(
        UUID batchId,
        long resumedCandidateCount,
        long activeCandidateCount,
        long remainingInterruptedCandidateCount
) {
}
