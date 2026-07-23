package org.monitoring.catchholebackend.domain.upload.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "설정집 원본 목록 항목")
public record SettingBookSummaryResponse(
        @Schema(description = "설정집 원본 ID") UUID id,
        @Schema(description = "원본 파일명", example = "작품 설정집.docx") String originalFilename,
        @Schema(description = "파일 크기(byte)", example = "20480") long fileSize,
        @Schema(description = "업로드 시각") LocalDateTime uploadedAt
) {
}
