package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "현재 상태 항목의 확정 여부와 출처")
public record WorkerAnalysisProvenancePayload(
        @Schema(description = "CONFIRMED 또는 PROVISIONAL") String confirmationStatus,
        @Schema(description = "실제 출처 회차. 직접 입력·legacy는 null입니다.", nullable = true) Integer sourceEpisodeNo,
        @Schema(description = "원문 근거로 연결되는 후보 ID") List<UUID> sourceCandidateIds,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "HUMAN은 사용자 확인, AUTOMATIC은 AI 자동 반영. 구기록은 생략합니다.", nullable = true) String reviewSource
) {
    public WorkerAnalysisProvenancePayload(String confirmationStatus, Integer sourceEpisodeNo, List<UUID> sourceCandidateIds) {
        this(confirmationStatus, sourceEpisodeNo, sourceCandidateIds, null);
    }
}
