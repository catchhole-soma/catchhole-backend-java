package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadStatus;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;

@Schema(description = "분석 대상 업로드 배치 요약 응답")
public record AnalysisJobTargetResponse(
        @Schema(description = "업로드 배치 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID batchId,

        @Schema(description = "업로드 방식", example = "MULTI_EPISODE_SINGLE_FILE")
        UploadType uploadType,

        @Schema(description = "업로드 출처", example = "FILE")
        UploadSourceType sourceType,

        @Schema(description = "업로드 배치 상태", example = "COMPLETED")
        UploadStatus status,

        @Schema(description = "업로드 파일 수", example = "2")
        int fileCount,

        @Schema(description = "분석 대상 시작 회차 번호", example = "1")
        Integer episodeStartNo,

        @Schema(description = "분석 대상 마지막 회차 번호", example = "10")
        Integer episodeEndNo,

        @Schema(description = "분석 대상 회차 수", example = "10")
        Integer episodeCount
) {
}
