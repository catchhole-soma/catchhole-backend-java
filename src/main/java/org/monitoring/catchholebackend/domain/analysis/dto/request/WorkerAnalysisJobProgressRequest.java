package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobCheckpointStage;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;

@Schema(description = "AI Worker 분석 작업 진행 단계 갱신 요청")
public record WorkerAnalysisJobProgressRequest(
        @Schema(description = "현재 처리 단계", example = "LLM 전처리")
        @NotBlank(message = "현재 처리 단계는 필수입니다.")
        @Size(max = 100, message = "현재 처리 단계는 100자 이하로 입력해주세요.")
        String currentStep,

        @Schema(description = "대상 회차에 명시적으로 적용할 처리 상태", example = "ANALYZING", nullable = true)
        EpisodeStatus episodeStatus,

        @Schema(description = "재개 시 사용할 완료 checkpoint", nullable = true)
        AnalysisJobCheckpointStage checkpointStage
) {
}
