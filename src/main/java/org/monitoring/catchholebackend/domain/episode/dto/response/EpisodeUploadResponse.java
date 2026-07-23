package org.monitoring.catchholebackend.domain.episode.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.dto.response.UploadFileResponse;
import org.monitoring.catchholebackend.domain.upload.type.UploadStatus;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeUploadType;

@Schema(description = "회차 업로드 결과 응답")
public record EpisodeUploadResponse(
        @Schema(
                description = "업로드 배치 ID",
                example = "550e8400-e29b-41d4-a716-446655440000",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID batchId,

        @Schema(
                description = "업로드 방식",
                example = "MULTI_EPISODE_SINGLE_FILE",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        EpisodeUploadType uploadType,

        @Schema(description = "업로드 처리 상태", example = "COMPLETED", requiredMode = Schema.RequiredMode.REQUIRED)
        UploadStatus status,

        @Schema(description = "생성된 회차 수", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        int episodeCount,

        @Schema(description = "생성된 회차 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<EpisodeSummaryResponse> createdEpisodes,

        @Schema(description = "업로드 파일 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<UploadFileResponse> files
) {
}
