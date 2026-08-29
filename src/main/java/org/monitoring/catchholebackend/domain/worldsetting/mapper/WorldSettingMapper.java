package org.monitoring.catchholebackend.domain.worldsetting.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCreateRequest;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupActionResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateGroupResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingCandidateResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingDetailResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingEvidenceSpanResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingListItemResponse;
import org.monitoring.catchholebackend.domain.worldsetting.dto.response.WorldSettingPropertyResponse;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCandidateGroupStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingCategory;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingComparisonStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonReason;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingRecomparisonScope;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation;
import org.springframework.stereotype.Component;

@Component
public class WorldSettingMapper {

    public WorldSetting toEntity(Work work, WorldSettingCreateRequest request) {
        return WorldSetting.create(
                work,
                request.category(),
                request.subjectName(),
                request.scopeName(),
                request.settingName(),
                request.settingValue()
        );
    }

    public WorldSetting toEntity(Work work, WorldSettingCandidateConfirmRequest request) {
        return WorldSetting.create(
                work,
                request.category(),
                request.subjectName(),
                request.scopeName(),
                request.settingName(),
                request.value()
        );
    }

    public WorldSettingListItemResponse toListItemResponse(WorldSetting worldSetting, String query) {
        WorldSetting.Property matchedProperty = findMatchedProperty(worldSetting, query);
        return new WorldSettingListItemResponse(
                worldSetting.getId(),
                worldSetting.getCategory(),
                worldSetting.getSubjectName(),
                worldSetting.getPropertyCount(),
                worldSetting.getVersion(),
                worldSetting.getUpdatedAt(),
                matchedProperty == null ? null : matchedProperty.scopeName(),
                matchedProperty == null ? null : matchedProperty.settingName(),
                matchedProperty == null ? null : matchedProperty.value()
        );
    }

    public WorldSettingDetailResponse toDetailResponse(
            WorldSetting worldSetting,
            List<WorldSettingCandidate> confirmedCandidates
    ) {
        List<WorldSetting.Property> properties = worldSetting.getProperties();
        List<WorldSettingDetailResponse.PropertyEvidence> propertyEvidence = properties.stream()
                .map(property -> toPropertyEvidenceResponse(property, confirmedCandidates))
                .toList();
        return new WorldSettingDetailResponse(
                worldSetting.getId(),
                worldSetting.getWork().getId(),
                worldSetting.getCategory(),
                worldSetting.getSubjectName(),
                properties.stream().map(this::toPropertyResponse).toList(),
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
        boolean userModified = candidate.getFinalOperation() != null
                && (!candidate.suggestedOperationMatches(candidate.getFinalOperation())
                || candidate.getFinalCategory() != candidate.getCategory()
                || !sameName(candidate.getFinalSubjectName(), suggestedSubjectName)
                || !sameName(candidate.getFinalScopeName(), candidate.getProposedScopeName())
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
                candidate.getScopeName(),
                candidate.getSettingName(),
                candidate.getExtractedValue(),
                toEvidenceSpans(candidate.getEvidenceSpans()),
                candidate.getExtractionConfidence(),
                targetWorldSetting == null ? null : targetWorldSetting.getId(),
                targetWorldSetting == null ? null : targetWorldSetting.getSubjectName(),
                candidate.getMatchedScopeName(),
                candidate.getMatchedPropertyName(),
                candidate.getConsolidationStatus(),
                candidate.getSuggestedOperation(),
                candidate.getComparisonReviewReason(),
                candidate.getProposedScopeName(),
                candidate.getProposedSettingName(),
                candidate.getBeforeValue(),
                candidate.getProposedValue(),
                candidate.getComparisonReason(),
                candidate.getBaseWorldSettingVersion(),
                candidate.getComparedAt(),
                candidate.getComparisonStatus(),
                publicComparisonErrorMessage(candidate),
                publicComparisonFailureCode(candidate),
                candidate.getReviewStatus(),
                userModified,
                candidate.getFinalOperation(),
                candidate.getFinalCategory(),
                candidate.getFinalSubjectName(),
                candidate.getFinalScopeName(),
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

    private String publicComparisonErrorMessage(WorldSettingCandidate candidate) {
        if (candidate.getComparisonStatus() == WorldSettingComparisonStatus.FAILED) {
            return publicComparisonFailureCode(candidate).getPublicMessage();
        }
        if (candidate.getComparisonStatus() == WorldSettingComparisonStatus.RECOMPARISON_REQUIRED) {
            return candidate.getComparisonErrorMessage();
        }
        return null;
    }

    private AnalysisFailureCode publicComparisonFailureCode(WorldSettingCandidate candidate) {
        if (candidate.getComparisonStatus() != WorldSettingComparisonStatus.FAILED) {
            return null;
        }
        return AnalysisFailureCode.orUnexpected(candidate.getComparisonFailureCode());
    }

    public WorldSettingCandidateGroupResponse toCandidateGroupResponse(
            String groupKey,
            List<WorldSettingCandidate> candidates
    ) {
        WorldSettingCandidate representative = candidates.getFirst();
        WorldSettingCategory category = representative.getEffectiveCategory();
        String subjectName = representative.getEffectiveSubjectName();
        return new WorldSettingCandidateGroupResponse(
                groupKey,
                category,
                subjectName,
                candidates.size(),
                operationCount(candidates, WorldSettingSuggestedOperation.ADD),
                operationCount(candidates, WorldSettingSuggestedOperation.UPDATE),
                operationCount(candidates, WorldSettingSuggestedOperation.MERGE),
                operationCount(candidates, WorldSettingSuggestedOperation.EXCLUDE),
                operationCount(candidates, WorldSettingSuggestedOperation.REVIEW_REQUIRED),
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
            WorldSettingSuggestedOperation operation
    ) {
        return (int) candidates.stream()
                .filter(candidate -> candidate.getEffectiveSuggestedOperation() == operation)
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
            WorldSetting.Property property,
            List<WorldSettingCandidate> candidates
    ) {
        List<WorldSettingDetailResponse.CandidateEvidence> history = candidates.stream()
                .filter(candidate -> sameName(candidate.getFinalScopeName(), property.scopeName()))
                .filter(candidate -> sameName(candidate.getFinalSettingName(), property.settingName()))
                .map(this::toCandidateEvidenceResponse)
                .toList();
        WorldSettingDetailResponse.CandidateEvidence latestEvidence = history.stream()
                .findFirst()
                .filter(evidence -> Objects.equals(evidence.value(), property.value()))
                .orElse(null);
        return new WorldSettingDetailResponse.PropertyEvidence(
                property.scopeName(),
                property.settingName(),
                latestEvidence,
                history
        );
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

    private WorldSetting.Property findMatchedProperty(WorldSetting worldSetting, String query) {
        String normalizedQuery = WorldSettingNameNormalizer.duplicateKey(query);
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return null;
        }
        return worldSetting.getProperties().stream()
                .filter(property -> property.scopeName() != null
                        && WorldSettingNameNormalizer.duplicateKey(property.scopeName()).contains(normalizedQuery)
                        || WorldSettingNameNormalizer.duplicateKey(property.settingName()).contains(normalizedQuery)
                        || WorldSettingNameNormalizer.duplicateKey(property.value()).contains(normalizedQuery))
                .findFirst()
                .orElse(null);
    }

    private WorldSettingPropertyResponse toPropertyResponse(WorldSetting.Property property) {
        return new WorldSettingPropertyResponse(
                property.scopeName(),
                property.settingName(),
                property.value()
        );
    }

    private boolean sameName(String left, String right) {
        return Objects.equals(
                WorldSettingNameNormalizer.duplicateKey(left),
                WorldSettingNameNormalizer.duplicateKey(right)
        );
    }
}
