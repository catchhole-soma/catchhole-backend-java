package org.monitoring.catchholebackend.domain.upload.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "설정집 전체 원문과 파일 메타데이터")
public record SettingBookResponse(
        @Schema(description = "설정집 원본 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "작품 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID workId,
        @Schema(
                description = "원본 파일명",
                example = "작품 설정집.docx",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String originalFilename,
        @Schema(
                description = "업로드 원본 MIME 타입",
                example = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String mimeType,
        @Schema(
                description = "업로드 원본 파일 크기(byte)",
                example = "20480",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        long fileSize,
        @Schema(
                description = "TXT 또는 DOCX에서 변환한 텍스트 원문",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String content,
        @Schema(description = "업로드 시각", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime uploadedAt
) {
}
