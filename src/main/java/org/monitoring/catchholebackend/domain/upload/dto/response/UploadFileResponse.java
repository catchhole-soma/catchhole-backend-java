package org.monitoring.catchholebackend.domain.upload.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileParseStatus;
import org.monitoring.catchholebackend.domain.upload.type.UploadFileRole;

@Schema(description = "업로드 파일 응답")
public record UploadFileResponse(
        @Schema(description = "업로드 파일 ID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "파일 역할", example = "EPISODE")
        UploadFileRole fileRole,

        @Schema(description = "원본 파일명", example = "EP_159_운명의_실타래.txt")
        String originalFilename,

        @Schema(description = "MIME 타입", example = "text/plain", nullable = true)
        String mimeType,

        @Schema(description = "파일 저장 위치", example = "s3://catchhole/uploads/...", nullable = true)
        String storageUrl,

        @Schema(description = "파일 크기", example = "20480")
        long fileSize,

        @Schema(description = "탐지된 시작 회차 번호", example = "1", nullable = true)
        Integer detectedEpisodeStartNo,

        @Schema(description = "탐지된 마지막 회차 번호", example = "10", nullable = true)
        Integer detectedEpisodeEndNo,

        @Schema(description = "탐지된 회차 수", example = "10", nullable = true)
        Integer detectedEpisodeCount,

        @Schema(description = "파일 파싱 상태", example = "PARSED")
        UploadFileParseStatus parseStatus
) {
}
