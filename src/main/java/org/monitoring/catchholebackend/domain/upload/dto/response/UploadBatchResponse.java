package org.monitoring.catchholebackend.domain.upload.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.type.UploadSourceType;
import org.monitoring.catchholebackend.domain.upload.type.UploadStatus;
import org.monitoring.catchholebackend.domain.upload.type.UploadType;

@Schema(description = "업로드 배치 응답")
public record UploadBatchResponse(
        @Schema(description = "업로드 배치 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "작품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID workId,

        @Schema(description = "회원 ID", example = "1")
        Long memberId,

        @Schema(description = "업로드 방식", example = "SINGLE_EPISODE")
        UploadType uploadType,

        @Schema(description = "입력 방식", example = "FILE")
        UploadSourceType sourceType,

        @Schema(description = "업로드 상태", example = "COMPLETED")
        UploadStatus status,

        @Schema(description = "파일 수", example = "2")
        int fileCount,

        @Schema(description = "업로드 완료 시각", example = "2026-06-11T16:30:00", nullable = true)
        LocalDateTime completedAt,

        @Schema(description = "업로드 파일 목록")
        List<UploadFileResponse> files
) {
}
