package org.monitoring.catchholebackend.domain.worldsetting.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingDetailResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListItemResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.springframework.stereotype.Component;

@Component
public class WorldSettingMapper {

    public WorldSetting toEntity(Work work, WorldSettingCreateRequest request) {
        return WorldSetting.create(
                work,
                request.category(),
                request.subjectName(),
                request.settingName(),
                request.settingValue()
        );
    }

    public WorldSetting toEntity(Work work, WorldSettingCandidateConfirmRequest request) {
        return WorldSetting.create(
                work,
                request.category(),
                request.subjectName(),
                request.settingName(),
                request.value()
        );
    }

    public WorldSettingListItemResponse toListItemResponse(WorldSetting worldSetting, String query) {
        Map.Entry<String, JsonNode> matchedProperty = findMatchedProperty(worldSetting, query);
        return new WorldSettingListItemResponse(
                worldSetting.getId(),
                worldSetting.getCategory(),
                worldSetting.getSubjectName(),
                worldSetting.getPropertyCount(),
                worldSetting.getVersion(),
                worldSetting.getUpdatedAt(),
                matchedProperty == null ? null : matchedProperty.getKey(),
                matchedProperty == null ? null : matchedProperty.getValue().asText()
        );
    }

    public WorldSettingDetailResponse toDetailResponse(
            WorldSetting worldSetting,
            List<WorldSettingCandidate> confirmedCandidates
    ) {
        Map<String, String> properties = toPropertiesMap(worldSetting.getPropertiesJson());
        List<WorldSettingDetailResponse.PropertyEvidence> propertyEvidence = properties.entrySet().stream()
                .map(property -> toPropertyEvidenceResponse(property, confirmedCandidates))
                .toList();
        return new WorldSettingDetailResponse(
                worldSetting.getId(),
                worldSetting.getWork().getId(),
                worldSetting.getCategory(),
                worldSetting.getSubjectName(),
                properties,
                properties.size(),
                worldSetting.getVersion(),
                propertyEvidence,
                worldSetting.getCreatedAt(),
                worldSetting.getUpdatedAt()
        );
    }

    public WorldSettingCandidateResponse toCandidateResponse(WorldSettingCandidate candidate) {
        WorldSetting targetWorldSetting = candidate.getTargetWorldSetting();
        String suggestedSubjectName = targetWorldSetting == null
                ? candidate.getSubjectName()
                : targetWorldSetting.getSubjectName();
        boolean userModified = candidate.getReviewStatus() != WorldSettingReviewStatus.PENDING_REVIEW
                && (candidate.getFinalOperation() != candidate.getSuggestedOperation()
                || candidate.getFinalCategory() != candidate.getCategory()
                || !sameName(candidate.getFinalSubjectName(), suggestedSubjectName)
                || !sameName(candidate.getFinalSettingName(), candidate.getProposedSettingName())
                || !Objects.equals(candidate.getFinalValue(), candidate.getProposedValue()));
        return new WorldSettingCandidateResponse(
                candidate.getId(),
                candidate.getWork().getId(),
                candidate.getSourceEpisode().getId(),
                candidate.getSourceEpisode().getEpisodeNo(),
                candidate.getAnalysisJob().getId(),
                candidate.getCategory(),
                candidate.getSubjectName(),
                candidate.getSettingName(),
                candidate.getExtractedValue(),
                candidate.getEvidenceSpans(),
                candidate.getExtractionConfidence(),
                targetWorldSetting == null ? null : targetWorldSetting.getId(),
                targetWorldSetting == null ? null : targetWorldSetting.getSubjectName(),
                candidate.getSuggestedOperation(),
                candidate.getProposedSettingName(),
                candidate.getBeforeValue(),
                candidate.getProposedValue(),
                candidate.getComparisonReason(),
                candidate.getBaseWorldSettingVersion(),
                candidate.getComparedAt(),
                candidate.getComparisonStatus(),
                candidate.getComparisonErrorMessage(),
                candidate.getReviewStatus(),
                userModified,
                candidate.getFinalOperation(),
                candidate.getFinalCategory(),
                candidate.getFinalSubjectName(),
                candidate.getFinalSettingName(),
                candidate.getFinalValue(),
                candidate.getReviewNote(),
                candidate.getReviewedBy() == null ? null : candidate.getReviewedBy().getId(),
                candidate.getReviewedBy() == null ? null : candidate.getReviewedBy().getDisplayName(),
                candidate.getReviewedAt(),
                candidate.getAppliedWorldSettingVersion(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }

    private WorldSettingDetailResponse.PropertyEvidence toPropertyEvidenceResponse(
            Map.Entry<String, String> property,
            List<WorldSettingCandidate> candidates
    ) {
        List<WorldSettingDetailResponse.CandidateEvidence> history = candidates.stream()
                .filter(candidate -> sameName(candidate.getFinalSettingName(), property.getKey()))
                .map(this::toCandidateEvidenceResponse)
                .toList();
        WorldSettingDetailResponse.CandidateEvidence latestEvidence = history.stream()
                .findFirst()
                .filter(evidence -> Objects.equals(evidence.value(), property.getValue()))
                .orElse(null);
        return new WorldSettingDetailResponse.PropertyEvidence(property.getKey(), latestEvidence, history);
    }

    private WorldSettingDetailResponse.CandidateEvidence toCandidateEvidenceResponse(
            WorldSettingCandidate candidate
    ) {
        return new WorldSettingDetailResponse.CandidateEvidence(
                candidate.getId(),
                candidate.getFinalOperation(),
                candidate.getFinalValue(),
                candidate.getSourceEpisode().getId(),
                candidate.getSourceEpisode().getEpisodeNo(),
                candidate.getEvidenceSpans(),
                candidate.getReviewedAt()
        );
    }

    private Map.Entry<String, JsonNode> findMatchedProperty(WorldSetting worldSetting, String query) {
        String normalizedQuery = WorldSettingNameNormalizer.duplicateKey(query);
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, JsonNode>> properties = new ArrayList<>();
        properties.addAll(worldSetting.getPropertiesJson().properties());
        return properties.stream()
                .filter(property -> WorldSettingNameNormalizer.duplicateKey(property.getKey()).contains(normalizedQuery)
                        || WorldSettingNameNormalizer.duplicateKey(property.getValue().asText()).contains(normalizedQuery))
                .findFirst()
                .orElse(null);
    }

    private Map<String, String> toPropertiesMap(JsonNode propertiesJson) {
        Map<String, String> properties = new LinkedHashMap<>();
        propertiesJson.properties().forEach(property ->
                properties.put(property.getKey(), property.getValue().asText()));
        return properties;
    }

    private boolean sameName(String left, String right) {
        return Objects.equals(
                WorldSettingNameNormalizer.duplicateKey(left),
                WorldSettingNameNormalizer.duplicateKey(right)
        );
    }
}
