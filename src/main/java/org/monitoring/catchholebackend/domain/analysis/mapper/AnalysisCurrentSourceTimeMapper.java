package org.monitoring.catchholebackend.domain.analysis.mapper;

import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCurrentSourceTimePayload;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;

public final class AnalysisCurrentSourceTimeMapper {
    private AnalysisCurrentSourceTimeMapper() {}
    public static WorkerAnalysisCurrentSourceTimePayload from(AnalysisJob job, Integer effectiveFrom, CharacterFactTemporalScope scope) {
        if (job == null) return new WorkerAnalysisCurrentSourceTimePayload(null, null, null, null, effectiveFrom, CharacterFactTemporalScope.UNKNOWN);
        return new WorkerAnalysisCurrentSourceTimePayload(job.getSourceKind(), job.getSourceEpisodeNo(),
                job.isSettingBookSource() ? job.getSourceSettingBookRevision().getReferenceTimeKind() : null,
                job.isSettingBookSource() ? job.getSourceSettingBookRevision().getReferenceEpisodeNo() : null,
                effectiveFrom, scope == null ? CharacterFactTemporalScope.UNKNOWN : scope);
    }

    public static WorkerAnalysisCurrentSourceTimePayload fromMetadata(
            org.monitoring.catchholebackend.domain.analysis.repository.CandidateReviewJobMetadata job,
            Integer effectiveFrom, CharacterFactTemporalScope scope) {
        if (job == null) return new WorkerAnalysisCurrentSourceTimePayload(null, null, null, null, effectiveFrom, CharacterFactTemporalScope.UNKNOWN);
        return new WorkerAnalysisCurrentSourceTimePayload(job.sourceKind(), job.sourceEpisodeNo(),
                job.referenceTimeKind(), job.referenceEpisodeNo(), effectiveFrom,
                scope == null ? CharacterFactTemporalScope.UNKNOWN : scope);
    }

    public static com.fasterxml.jackson.databind.node.ObjectNode toJson(WorkerAnalysisCurrentSourceTimePayload time) {
        var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("sourceKind", time.sourceKind() == null ? null : time.sourceKind().name());
        node.put("sourceEpisodeNo", time.sourceEpisodeNo());
        node.put("referenceTimeKind", time.referenceTimeKind() == null ? null : time.referenceTimeKind().name());
        node.put("referenceEpisodeNo", time.referenceEpisodeNo());
        node.put("effectiveFromEpisodeNo", time.effectiveFromEpisodeNo());
        node.put("temporalScope", time.temporalScope().name());
        return node;
    }

    public static java.util.List<WorkerAnalysisCurrentSourceTimePayload> fromJson(com.fasterxml.jackson.databind.JsonNode entries) {
        if (entries == null || !entries.isArray()) return java.util.List.of();
        java.util.List<WorkerAnalysisCurrentSourceTimePayload> result = new java.util.ArrayList<>();
        for (var entry : entries) {
            result.add(new WorkerAnalysisCurrentSourceTimePayload(
                    entry.path("sourceKind").isTextual() ? org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.valueOf(entry.path("sourceKind").asText()) : null,
                    integer(entry, "sourceEpisodeNo"),
                    entry.path("referenceTimeKind").isTextual() ? org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind.valueOf(entry.path("referenceTimeKind").asText()) : null,
                    integer(entry, "referenceEpisodeNo"), integer(entry, "effectiveFromEpisodeNo"),
                    entry.path("temporalScope").isTextual() ? CharacterFactTemporalScope.valueOf(entry.path("temporalScope").asText()) : CharacterFactTemporalScope.UNKNOWN));
        }
        return java.util.List.copyOf(result);
    }

    public static Integer integer(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).intValue() : null;
    }
}
