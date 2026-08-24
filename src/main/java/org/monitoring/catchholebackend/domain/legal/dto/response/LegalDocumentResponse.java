package org.monitoring.catchholebackend.domain.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentStatus;
import org.monitoring.catchholebackend.domain.legal.type.LegalDocumentType;

@Schema(description = "게시된 법률 문서 원문")
public record LegalDocumentResponse(
        @Schema(description = "회원 동의 이력과 연결할 문서 식별자", example = "3")
        Long id,

        @Schema(description = "문서 종류", example = "TERMS_OF_SERVICE")
        LegalDocumentType documentType,

        @Schema(description = "문서 언어·지역", example = "ko-KR")
        String locale,

        @Schema(description = "문서 버전", example = "2026-08-24")
        String documentVersion,

        @Schema(description = "문서 제목", example = "CatchHole 이용약관")
        String title,

        @Schema(description = "Markdown 원문")
        String contentMarkdown,

        @Schema(description = "UTF-8 Markdown 원문의 SHA-256", example = "6f1ed002ab5595859014ebf0951522d9...")
        String contentHash,

        @Schema(description = "문서 공개 상태", example = "PUBLISHED")
        LegalDocumentStatus status,

        @Schema(description = "시행일", example = "2026-08-24")
        LocalDate effectiveDate,

        @Schema(description = "게시 시각", example = "2026-08-24T18:00:00")
        LocalDateTime publishedAt
) {
}
