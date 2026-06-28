package org.monitoring.catchholebackend.domain.character.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.monitoring.catchholebackend.domain.character.type.SettingValueType;

@Schema(description = "설정 후보 응답")
public record SettingCandidateResponse(
        @Schema(description = "설정 후보 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d333")
        UUID id,

        @Schema(description = "작품 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d444")
        UUID workId,

        @Schema(description = "후보가 추출된 회차 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d222")
        UUID episodeId,

        @Schema(description = "원문 근거 청크 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d111")
        UUID sourceChunkId,

        @Schema(description = "후보를 만든 분석 작업 ID", example = "01970c2e-7e6d-7000-8e5d-2a9bc4b6d555")
        UUID analysisJobId,

        @Schema(description = "설정 대상 유형", example = "CHARACTER")
        SettingEntityType entityType,

        @Schema(description = "설정 대상 이름", example = "아리아")
        String entityName,

        @Schema(description = "설정 속성명", example = "level")
        String attributeName,

        @Schema(description = "목록/검색 표시용 설정 값", example = "23")
        String attributeValue,

        @Schema(description = "설정 값 타입", example = "NUMBER")
        SettingValueType valueType,

        @Schema(description = "구조화된 설정 값 JSON")
        Object valueJson,

        @Schema(description = "원문 근거 span JSON")
        Object evidenceSpans,

        @Schema(description = "AI 추출 신뢰도", example = "0.9500")
        BigDecimal confidence,

        @Schema(description = "후보 검토 상태", example = "PENDING_REVIEW")
        SettingCandidateReviewStatus reviewStatus,

        @Schema(description = "AI Worker 원본 응답 JSON")
        Object rawAiResultJson,

        @Schema(description = "생성 시각", example = "2026-06-14T10:29:00")
        LocalDateTime createdAt,

        @Schema(description = "수정 시각", example = "2026-06-14T10:29:00")
        LocalDateTime updatedAt
) {
}
