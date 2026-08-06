package org.monitoring.catchholebackend.domain.worldsetting.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;

@Schema(description = "세계관 대상 상세")
public record WorldSettingDetailResponse(
        UUID id,
        UUID workId,
        WorldSettingCategory category,
        String subjectName,
        Map<String, String> properties,
        int propertyCount,
        @Schema(description = "낙관적 잠금 버전", requiredMode = Schema.RequiredMode.REQUIRED)
        long version,
        List<PropertyEvidence> propertyEvidence,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    @Schema(description = "현재 설정명별 후보 근거와 확정 이력")
    public record PropertyEvidence(
            String settingName,
            @Schema(nullable = true) CandidateEvidence latestEvidence,
            List<CandidateEvidence> history
    ) {
    }

    @Schema(description = "후보 확정으로 연결된 원문 근거")
    public record CandidateEvidence(
            UUID candidateId,
            WorldSettingOperation operation,
            String value,
            UUID sourceEpisodeId,
            Integer sourceEpisodeNo,
            Object evidenceSpans,
            LocalDateTime reviewedAt
    ) {
    }
}
