package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;

@Schema(description = "업로드 출처와 분리된 누적 분석 실행. Job 성공과 변경 기록 완성을 구분합니다.")
public record AnalysisRunResponse(
        @Schema(description = "실행 중 고정한 모드") AnalysisMode mode,
        @Schema(description = "분석 실행 ID") UUID runId,
        @Schema(description = "실행 버전") long generation,
        @Schema(description = "실행 내 0부터 시작하는 회차 순서") int sequence,
        @Schema(description = "직전 Job ID", nullable = true) UUID predecessorJobId,
        @Schema(description = "후속 회차가 사용할 변경 기록 상태") AnalysisJournalStatus journalStatus,
        @Schema(description = "재실행이 필요한 무효화 사유", nullable = true) String invalidationReason
) {
}
