package org.monitoring.catchholebackend.domain.worldsetting.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingDetailResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingEvidenceSpanResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListItemResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCandidateGroupStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonScope;
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
                toEvidenceSpans(candidate.getEvidenceSpans()),
                candidate.getExtractionConfidence(),
                targetWorldSetting == null ? null : targetWorldSetting.getId(),
                targetWorldSetting == null ? null : targetWorldSetting.getSubjectName(),
                candidate.getConsolidationStatus(),
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

    public WorldSettingCandidateGroupResponse toCandidateGroupResponse(
            String groupKey,
            List<WorldSettingCandidate> candidates
    ) {
        WorldSettingCandidate representative = candidates.getFirst();
        WorldSetting representativeTarget = representative.getTargetWorldSetting();
        WorldSettingCategory category = representativeTarget == null
                ? representative.getCategory()
                : representativeTarget.getCategory();
        String subjectName = representativeTarget == null
                ? representative.getSubjectName()
                : representativeTarget.getSubjectName();
        return new WorldSettingCandidateGroupResponse(
                groupKey,
                category,
                subjectName,
                candidates.size(),
                operationCount(candidates, WorldSettingOperation.ADD),
                operationCount(candidates, WorldSettingOperation.UPDATE),
                operationCount(candidates, WorldSettingOperation.MERGE),
                operationCount(candidates, WorldSettingOperation.EXCLUDE),
                candidates.stream()
                        .map(candidate -> candidate.getSourceEpisode().getEpisodeNo())
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList(),
                groupStatus(candidates),
                recomparisonScope(candidates),
                candidates.stream().map(this::toCandidateResponse).toList()
        );
    }

    public WorldSettingCandidateGroupActionResponse toCandidateGroupActionResponse(
            String groupKey,
            List<WorldSettingCandidate> candidates,
            WorldSetting worldSetting
    ) {
        return new WorldSettingCandidateGroupActionResponse(
                groupKey,
                worldSetting == null ? null : worldSetting.getId(),
                worldSetting == null ? null : worldSetting.getVersion(),
                candidates.stream().map(this::toCandidateResponse).toList()
        );
    }

    private int operationCount(
            List<WorldSettingCandidate> candidates,
            WorldSettingOperation operation
    ) {
        return (int) candidates.stream()
                .filter(candidate -> candidate.getSuggestedOperation() == operation)
                .count();
    }

    private WorldSettingCandidateGroupStatus groupStatus(List<WorldSettingCandidate> candidates) {
        if (hasComparisonStatus(candidates, WorldSettingComparisonStatus.FAILED)) {
            return WorldSettingCandidateGroupStatus.FAILED;
        }
        if (hasComparisonStatus(candidates, WorldSettingComparisonStatus.RECOMPARISON_REQUIRED)) {
            return WorldSettingCandidateGroupStatus.RECOMPARISON_REQUIRED;
        }
        if (hasComparisonStatus(candidates, WorldSettingComparisonStatus.PROCESSING)) {
            return WorldSettingCandidateGroupStatus.PROCESSING;
        }
        if (hasComparisonStatus(candidates, WorldSettingComparisonStatus.PENDING)) {
            return WorldSettingCandidateGroupStatus.PENDING;
        }
        return WorldSettingCandidateGroupStatus.READY;
    }

    private boolean hasComparisonStatus(
            List<WorldSettingCandidate> candidates,
            WorldSettingComparisonStatus status
    ) {
        return candidates.stream().anyMatch(candidate -> candidate.getComparisonStatus() == status);
    }

    private WorldSettingRecomparisonScope recomparisonScope(List<WorldSettingCandidate> candidates) {
        List<String> reasons = candidates.stream()
                .filter(candidate -> candidate.getComparisonStatus()
                        == WorldSettingComparisonStatus.RECOMPARISON_REQUIRED)
                .map(WorldSettingCandidate::getComparisonErrorMessage)
                .filter(Objects::nonNull)
                .toList();
        if (reasons.isEmpty()) {
            return null;
        }
        boolean groupReason = reasons.stream().anyMatch(reason ->
                reason.equals(WorldSettingRecomparisonReason.TARGET_CREATED.getMessage())
                        || reason.equals(WorldSettingRecomparisonReason.TARGET_MISSING.getMessage())
                        || reason.equals(WorldSettingRecomparisonReason.TARGET_IDENTITY_CHANGED.getMessage())
        );
        return groupReason ? WorldSettingRecomparisonScope.GROUP : WorldSettingRecomparisonScope.ROW;
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
                toEvidenceSpans(candidate.getEvidenceSpans()),
                candidate.getReviewedAt()
        );
    }

    private List<WorldSettingEvidenceSpanResponse> toEvidenceSpans(JsonNode value) {
        if (value == null || !value.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(value.spliterator(), false)
                .filter(JsonNode::isObject)
                .map(span -> new WorldSettingEvidenceSpanResponse(
                        textValue(span, "quote"),
                        integerValue(span, "startOffset"),
                        integerValue(span, "endOffset")
                ))
                .filter(span -> span.quote() != null && !span.quote().isBlank())
                .toList();
    }

    private String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer integerValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || !value.isIntegralNumber() ? null : value.asInt();
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
