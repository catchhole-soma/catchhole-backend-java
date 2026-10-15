package org.monitoring.catchholebackend.domain.analysis.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisProvenancePayload;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

/** 고정 상태의 JSON provenance를 MVC 값 타입으로 변환한다. */
public final class AnalysisProvenanceMapper {
    private AnalysisProvenanceMapper() {}

    public static WorkerAnalysisProvenancePayload from(JsonNode source, String fallbackStatus) {
        List<UUID> ids = new ArrayList<>();
        source.path("sourceCandidateIds").forEach(id -> ids.add(UUID.fromString(id.asText())));
        AnalysisSourceKind kind = source.path("sourceKind").isTextual()
                ? AnalysisSourceKind.valueOf(source.path("sourceKind").asText()) : null;
        return new WorkerAnalysisProvenancePayload(source.path("confirmationStatus").asText(fallbackStatus),
                AnalysisCurrentSourceTimeMapper.integer(source, "sourceEpisodeNo"), List.copyOf(ids),
                text(source, "reviewSource"), kind, uuid(source, "sourceSettingBookId"), text(source, "sourceSettingBookName"),
                uuid(source, "sourceSettingBookRevisionId"), AnalysisCurrentSourceTimeMapper.integer(source, "sourceSettingBookRevisionNo"),
                kind == AnalysisSourceKind.SETTING_BOOK ? source.path("sourceReferenceTimeKind").isTextual()
                        ? SettingBookReferenceTimeKind.valueOf(source.path("sourceReferenceTimeKind").asText()) : SettingBookReferenceTimeKind.UNSPECIFIED : null,
                AnalysisCurrentSourceTimeMapper.integer(source, "sourceReferenceEpisodeNo"),
                AnalysisCurrentSourceTimeMapper.integer(source, "effectiveFromEpisodeNo"),
                source.path("currentValueProtected").isBoolean() ? source.path("currentValueProtected").booleanValue() : null,
                AnalysisCurrentSourceTimeMapper.fromJson(source.path("currentSourceTimes")));
    }

    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private static UUID uuid(JsonNode node, String field) { return node.path(field).isTextual() ? UUID.fromString(node.path(field).asText()) : null; }
}
