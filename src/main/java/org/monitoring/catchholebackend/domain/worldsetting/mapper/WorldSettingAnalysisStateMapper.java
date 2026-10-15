package org.monitoring.catchholebackend.domain.worldsetting.mapper;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.springframework.stereotype.Component;

@Component
public class WorldSettingAnalysisStateMapper {

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public static String persistedRef(UUID worldSettingId) {
        return "world:" + Objects.requireNonNull(worldSettingId);
    }

    public static String provisionalRef(UUID sourceCandidateId) {
        return "provisional-world:" + Objects.requireNonNull(sourceCandidateId);
    }

    public ObjectNode toState(WorldSetting setting) {
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        state.put("actualWorldSettingId", setting.getId().toString());
        state.putNull("provisionalSubjectKey");
        state.put("category", setting.getCategory().name());
        state.put("subjectName", setting.getSubjectName());
        state.put("normalizedSubjectName", setting.getNormalizedSubjectName());
        state.put("version", setting.getVersion());
        state.set("propertiesJson", setting.getPropertiesJson().deepCopy());
        var protectedPaths = state.putArray("protectedPaths");
        for (var path : setting.getManuallyEditedPaths()) {
            try {
                var parts = objectMapper.readTree(path.asText());
                protectedPaths.addObject().put("scopeName", parts.path(0).isNull() ? null : parts.path(0).asText()).put("settingName", parts.path(1).asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("수동 세계관 경로 표지가 올바르지 않습니다.", exception);
            }
        }
        ObjectNode provenance = state.putObject("provenanceByPath");
        for (WorldSetting.Property property : setting.getProperties()) {
            ObjectNode source = provenance.putObject(pathKey(property.scopeName(), property.settingName()));
            source.put("confirmationStatus", "CONFIRMED");
            source.putNull("sourceEpisodeNo");
            source.putArray("sourceCandidateIds");
        }
        return state;
    }

    public static String pathKey(String scopeName, String settingName) {
        // 이름에 구분 문자가 있어도 root와 scope 전체 경로를 충돌 없이 구분한다.
        return JsonNodeFactory.instance.arrayNode().add(scopeName).add(settingName).toString();
    }

    public void addConfirmedProvenance(ObjectNode state,
            java.util.List<org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate> candidates) {
        ObjectNode identity = state.putObject("provenance");
        identity.put("confirmationStatus", "CONFIRMED");
        identity.putNull("sourceEpisodeNo");
        identity.putArray("sourceCandidateIds");
        identity.put("reviewSource", !candidates.isEmpty()
                && candidates.stream().allMatch(candidate -> candidate.isReviewedAutomatically()) ? "AUTOMATIC" : "HUMAN");
        candidates.stream().findFirst().ifPresent(candidate -> org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(identity, candidate.getAnalysisJob()));
        var properties = new org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView(
                state.path("propertiesJson"));
        for (var property : properties.properties()) {
            ObjectNode source = (ObjectNode) state.path("provenanceByPath").path(pathKey(property.scopeName(), property.settingName()));
            var currentSources = org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.forProperty(
                    candidates, property.scopeName(), property.settingName(), property.value());
            source.put("reviewSource", !currentSources.isEmpty() && currentSources.stream().allMatch(row -> row.isReviewedAutomatically()) ? "AUTOMATIC" : "HUMAN");
            source.put("currentValueProtected", protectedPaths(state).stream().anyMatch(path -> org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.samePath(
                    path.scopeName(), path.settingName(), property.scopeName(), property.settingName())));
            var times = source.putArray("currentSourceTimes");
            if (currentSources.isEmpty()) times.add(org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                    org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(null, null, null)));
            var ids = source.putArray("sourceCandidateIds");
            for (var candidate : currentSources) {
                times.add(org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.toJson(
                        org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper.from(candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope())));
                ids.add(candidate.getId().toString());
            }
            if (!currentSources.isEmpty()) {
                var candidate = currentSources.getLast();
                source.put("sourceEpisodeNo", candidate.getSourceEpisode() == null ? null : candidate.getSourceEpisode().getEpisodeNo());
                source.put("effectiveFromEpisodeNo", candidate.getEffectiveFromEpisodeNo());
                org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisSourceMetadata.put(source, candidate.getAnalysisJob());
            }
        }
    }

    public org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse.Subject
            toSubject(com.fasterxml.jackson.databind.JsonNode state) {
        return new org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse.Subject(
                actualId(state), state.path("subjectName").asText(), provisionalKey(state),
                java.util.List.of(), toJsonValue(state.path("provenance")), identityEvidence(state), protectedPaths(state));
    }

    public org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse.Target
            toTarget(com.fasterxml.jackson.databind.JsonNode state) {
        var view = new org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView(
                state.path("propertiesJson"));
        return new org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingComparisonContextResponse.Target(
                actualId(state), state.path("subjectName").asText(), view.properties().stream().map(property ->
                new org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingPropertyResponse(
                        property.scopeName(), property.settingName(), property.value(),
                        toJsonValue(state.path("provenanceByPath").path(pathKey(property.scopeName(), property.settingName())))))
                        .toList(), state.path("version").asLong(), provisionalKey(state), protectedPaths(state));
    }

    public java.util.List<org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingProtectedPathPayload> protectedPaths(com.fasterxml.jackson.databind.JsonNode state) {
        java.util.List<org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingProtectedPathPayload> result = new java.util.ArrayList<>();
        for (var path : state.path("protectedPaths")) {
            result.add(new org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingProtectedPathPayload(
                    path.path("scopeName").isNull() ? null : path.path("scopeName").asText(), path.path("settingName").asText()));
        }
        return java.util.List.copyOf(result);
    }

    private java.util.List<org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingCandidatePayload.EvidenceSpan>
            identityEvidence(com.fasterxml.jackson.databind.JsonNode state) {
        if (!state.path("identityEvidence").isArray()) {
            return java.util.List.of();
        }
        return objectMapper.convertValue(state.path("identityEvidence"),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public UUID actualId(com.fasterxml.jackson.databind.JsonNode state) {
        return state.path("actualWorldSettingId").isTextual()
                ? UUID.fromString(state.path("actualWorldSettingId").asText()) : null;
    }

    public String provisionalKey(com.fasterxml.jackson.databind.JsonNode state) {
        return state.path("provisionalSubjectKey").isTextual() ? state.path("provisionalSubjectKey").asText() : null;
    }

    public Object toJsonValue(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : objectMapper.convertValue(node, Object.class);
    }
}
