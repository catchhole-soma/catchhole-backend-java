package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode;

@Schema(description = "큰 상태 JSON과 결과 본문을 읽지 않는 분석 진행 상태")
public record AnalysisJobProgressResponse(
        @Schema(description = "분석 작업 ID") UUID id,
        @Schema(description = "단일 회차 ID. 과거 복수 대상 작업에는 없을 수 있습니다.", nullable = true) UUID episodeId,
        @Schema(description = "공개 분석 유형") AnalysisJobType jobType,
        @Schema(description = "분석 작업 상태") AnalysisJobStatus status,
        @Schema(description = "설정 반영 방식") AnalysisReviewMode reviewMode,
        @Schema(description = "설정 자동 반영 완료 시각", nullable = true) LocalDateTime automaticAppliedAt,
        @Schema(description = "실패 상태에만 제공하는 공개 실패 코드", nullable = true) AnalysisFailureCode failureCode,
        @Schema(description = "일반 분석의 추출 결과를 보존한 세계관 비교 토큰 중단 여부") boolean tokenInterruptedAfterExtraction,
        @Schema(description = "누적 실행 상태. 일반 분석은 null입니다.", nullable = true) AnalysisRunResponse analysisRun,
        @Schema(description = "고정 대상 회차. 스냅샷이 없을 때만 단일 회차를 사용합니다.") List<AnalysisJobProgressEpisodeResponse> episodes
) {}
