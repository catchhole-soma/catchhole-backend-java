package org.monitoring.catchholebackend.domain.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;
import com.fasterxml.jackson.annotation.JsonInclude;

@Schema(description = "분석 당시 불변 원문 출처. 설정집은 회차 번호가 없습니다.")
public record AnalysisSourceResponse(
        AnalysisSourceKind sourceKind,
        @Schema(nullable = true) UUID episodeId,
        @Schema(nullable = true) Integer episodeNo,
        @Schema(nullable = true) UUID settingBookId,
        @Schema(nullable = true) String settingBookName,
        @Schema(nullable = true) UUID revisionId,
        @Schema(nullable = true) Integer revisionNo,
        @Schema(nullable = true) String contentHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(nullable = true) SettingBookReferenceTimeKind referenceTimeKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(nullable = true) Integer referenceEpisodeNo) {
    public AnalysisSourceResponse(AnalysisSourceKind sourceKind, UUID episodeId, Integer episodeNo, UUID settingBookId,
            String settingBookName, UUID revisionId, Integer revisionNo, String contentHash) {
        this(sourceKind, episodeId, episodeNo, settingBookId, settingBookName, revisionId, revisionNo, contentHash,
                sourceKind == AnalysisSourceKind.SETTING_BOOK ? SettingBookReferenceTimeKind.UNSPECIFIED : null, null);
    }
    public static AnalysisSourceResponse from(AnalysisJob job) {
        if (job == null) return null;
        if (job.isSettingBookSource()) return new AnalysisSourceResponse(job.getSourceKind(), null, null,
                job.getSourceSettingBook().getId(), job.getSourceSettingBook().getOriginalFilename(),
                job.getSourceSettingBookRevision().getId(), job.getSourceSettingBookRevision().getRevisionNo(), job.getSourceContentHash(),
                job.getSourceSettingBookRevision().getReferenceTimeKind(), job.getSourceSettingBookRevision().getReferenceEpisodeNo());
        return new AnalysisSourceResponse(AnalysisSourceKind.EPISODE, job.getEpisode() == null ? null : job.getEpisode().getId(),
                job.getSourceEpisodeNo(), null, null, null, null, job.getSourceContentHash());
    }
    public static AnalysisSourceResponse fromProvenance(JsonNode p) {
        if (p == null || !p.isObject()) return null;
        return new AnalysisSourceResponse("SETTING_BOOK".equals(p.path("sourceKind").asText()) ? AnalysisSourceKind.SETTING_BOOK : AnalysisSourceKind.EPISODE,
                uuid(p, "sourceEpisodeId"), p.path("sourceEpisodeNo").isIntegralNumber() ? p.path("sourceEpisodeNo").asInt() : null,
                uuid(p, "sourceSettingBookId"), p.path("sourceSettingBookName").isTextual() ? p.path("sourceSettingBookName").asText() : null,
                uuid(p, "sourceSettingBookRevisionId"), p.path("sourceSettingBookRevisionNo").isIntegralNumber() ? p.path("sourceSettingBookRevisionNo").asInt() : null,
                p.path("sourceHash").isTextual() ? p.path("sourceHash").asText() : null,
                "SETTING_BOOK".equals(p.path("sourceKind").asText())
                        ? p.path("sourceReferenceTimeKind").isTextual() ? SettingBookReferenceTimeKind.valueOf(p.path("sourceReferenceTimeKind").asText()) : SettingBookReferenceTimeKind.UNSPECIFIED : null,
                p.path("sourceReferenceEpisodeNo").isIntegralNumber() ? p.path("sourceReferenceEpisodeNo").asInt() : null);
    }
    private static UUID uuid(JsonNode p, String key) { return p.path(key).isTextual() ? UUID.fromString(p.path(key).asText()) : null; }
}
