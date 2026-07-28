package org.monitoring.catchholebackend.domain.upload.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "설정집 읽기 전용 원문")
public record SettingBookResponse(
        @Schema(description = "설정집 원본 ID") UUID id,
        @Schema(description = "작품 ID") UUID workId,
        @Schema(description = "원본 파일명", example = "작품 설정집.docx") String originalFilename,
        @Schema(description = "파일 크기(byte)", example = "20480") long fileSize,
        @Schema(description = "TXT 또는 DOCX에서 변환한 텍스트 원문") String content,
        @Schema(description = "업로드 시각") LocalDateTime uploadedAt
) {
}
