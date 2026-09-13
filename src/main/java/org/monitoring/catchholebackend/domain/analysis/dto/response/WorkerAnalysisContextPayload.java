package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

@Schema(description = "누적 실행의 고정 입력 버전. 모델용 참조와 별도로 Worker가 검증합니다.")
public record WorkerAnalysisContextPayload(
        @Schema(description = "분석 실행 ID") UUID runId,
        @Schema(description = "실행 generation") long generation,
        @Schema(description = "회차 시작 입력 상태 SHA-256") String inputStateHash,
        @Schema(description = "원문 SHA-256") String sourceHash,
        @Schema(description = "상태·기록 계약 버전") int formatVersion,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "같은 작품의 앞 회차에서 아직 반영하지 않은 참고 내용. 이전 업로드 묶음도 포함하며 확정 사실이나 연결 가능한 대상은 아닙니다.") List<WorkerAnalysisReferencePayload> unresolvedReferences
) {
    public WorkerAnalysisContextPayload(UUID runId, long generation, String inputStateHash, String sourceHash, int formatVersion) {
        this(runId, generation, inputStateHash, sourceHash, formatVersion, List.of());
    }
}
