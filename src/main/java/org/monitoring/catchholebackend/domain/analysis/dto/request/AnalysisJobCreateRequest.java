package org.monitoring.catchholebackend.domain.analysis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;

@Schema(description = "분석 작업 생성 요청")
public record AnalysisJobCreateRequest(
        @NotNull(message = "분석 작업 유형은 필수입니다.")
        @Schema(description = "분석 작업 유형", example = "EPISODE_VALIDATION")
        AnalysisJobType jobType,

        @NotNull(message = "분석 대상 업로드 배치 ID는 필수입니다.")
        @Schema(description = "분석 대상 업로드 배치 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID batchId,

        @Schema(
                description = "분석 범위 회차 ID. 없으면 batch의 각 회차별 작업을 생성하고, 있으면 해당 회차 작업만 생성합니다.",
                nullable = true
        )
        UUID episodeId,

        @Schema(description = "실행 중 고정할 분석 입력 정책. 자동 반영은 ORDERED_PROVISIONAL로 고정하며, 직접 검토에서 생략하면 CONFIRMED_ONLY입니다.", nullable = true)
        AnalysisMode analysisMode,

        @Schema(description = "설정 반영 방식. 새 설정 추출·재분석은 생략 시 자동 반영하며, 단일 회차는 MANUAL로 직접 검토를 선택할 수 있습니다. 다회차는 항상 자동 반영하고 회차 검증은 생략 시 직접 검토입니다.", nullable = true)
        AnalysisReviewMode reviewMode,

        @Schema(description = "시작 전 안내한 애매한 후보를 종료하고 분석할지 명시적으로 선택합니다. 기본 false입니다.", example = "true")
        Boolean closeAmbiguousCandidates,

        @Size(max = 64)
        @Schema(description = "시작 사전 확인에서 받은 revision. 후보 종료에 동의했을 때 함께 전달합니다.", example = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", nullable = true)
        String reviewGateRevision
) {
    public AnalysisJobCreateRequest(AnalysisJobType jobType, UUID batchId, UUID episodeId,
            AnalysisMode analysisMode, AnalysisReviewMode reviewMode) {
        this(jobType, batchId, episodeId, analysisMode, reviewMode, null, null);
    }

    public AnalysisJobCreateRequest(AnalysisJobType jobType, UUID batchId, UUID episodeId, AnalysisMode analysisMode) {
        this(jobType, batchId, episodeId, analysisMode, null);
    }

    public AnalysisJobCreateRequest(AnalysisJobType jobType, UUID batchId, UUID episodeId) {
        this(jobType, batchId, episodeId, null, null);
    }

    public AnalysisMode effectiveAnalysisMode() {
        return analysisMode == null ? AnalysisMode.CONFIRMED_ONLY : analysisMode;
    }

    public AnalysisReviewMode effectiveReviewMode() {
        return reviewMode != null ? reviewMode : jobType == AnalysisJobType.SETTING_EXTRACTION
                ? AnalysisReviewMode.AUTOMATIC : AnalysisReviewMode.MANUAL;
    }
}
