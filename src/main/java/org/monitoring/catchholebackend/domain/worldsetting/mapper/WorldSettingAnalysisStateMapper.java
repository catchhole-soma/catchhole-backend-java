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
        var properties = new org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView(
                state.path("propertiesJson"));
        for (var property : properties.properties()) {
            ObjectNode source = (ObjectNode) state.path("provenanceByPath").path(pathKey(property.scopeName(), property.settingName()));
            source.put("reviewSource", "HUMAN");
            candidates.stream().filter(candidate -> !candidate.isHistoryOnly()).filter(candidate -> Objects.equals(candidate.getFinalScopeName(), property.scopeName())
                    && Objects.equals(candidate.getFinalSettingName(), property.settingName())
                    && Objects.equals(candidate.getFinalValue(), property.value())).findFirst().ifPresent(candidate -> {
                        source.put("sourceEpisodeNo", candidate.getSourceEpisode() == null ? null : candidate.getSourceEpisode().getEpisodeNo());
                        source.putArray("sourceCandidateIds").add(candidate.getId().toString());
                        source.put("reviewSource", candidate.isReviewedAutomatically() ? "AUTOMATIC" : "HUMAN");
                    });
        }
    }

    public org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse.Subject
            toSubject(com.fasterxml.jackson.databind.JsonNode state) {
        return new org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorkerWorldSettingSubjectPageResponse.Subject(
                actualId(state), state.path("subjectName").asText(), provisionalKey(state),
                java.util.List.of(), toJsonValue(state.path("provenance")), identityEvidence(state));
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
                        .toList(), state.path("version").asLong(), provisionalKey(state));
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
