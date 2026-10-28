package org.monitoring.catchholebackend.domain.worldsetting.service;

import org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateSourceGuard;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.worldsetting.dto.request.WorldSettingCandidateGroupConfirmRequest;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSetting;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCandidateChronology;
import org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingPropertyView;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisCurrentSourceTimeMapper;
import org.monitoring.catchholebackend.domain.analysis.processor.SettingBookTemporalPolicy;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingReviewStatus;

/** 사용자 선택을 실제 대상과 순차 적용해 전체 검증한 뒤 한 번에 반영할 최종값을 만든다. */
@Component
@RequiredArgsConstructor
public class WorldSettingAnalysisConfirmation {

    private final WorldSettingCandidateRepository repository;
    private final AnalysisCandidateSourceGuard sourceGuard;

    public void assertExtractionAllowed(org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob job) {
        sourceGuard.assertCurrent(job, job.getEpisode());
    }

    public void assertReviewSourceCurrent(WorldSettingCandidate candidate) {
        sourceGuard.assertCurrent(candidate.getAnalysisJob(), candidate.getSourceEpisode());
    }

    public AutomaticReviewHoldReason automaticHold(WorldSettingCandidate candidate, WorldSetting actual) {
        if (candidate.getAnalysisJob().isSettingBookSource()
                && !org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingExtractionAssertion.isAsserted(candidate.getRawExtractionJson())) {
            return AutomaticReviewHoldReason.REVIEW_REQUIRED;
        }
        var incoming = AnalysisCurrentSourceTimeMapper.from(candidate.getAnalysisJob(), candidate.getEffectiveFromEpisodeNo(), candidate.getTemporalScope());
        if (SettingBookTemporalPolicy.requiresReview(incoming, List.of(), false)) return AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
        if (candidate.getSuggestedOperation() == org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.EXCLUDE) return null;
        if (actual == null) return null;
        java.util.Map<String, String[]> affected = new java.util.LinkedHashMap<>();
        String scope = candidate.getProposedScopeName(); String key = candidate.getProposedSettingName();
        if (key == null) key = candidate.getSettingName();
        affected.put("destination", new String[]{scope, key});
        var comparison = candidate.getComparisonDecision();
        if (candidate.getMatchedPropertyName() != null && (!Objects.equals(candidate.getMatchedScopeName(), scope)
                || !Objects.equals(candidate.getMatchedPropertyName(), key))) affected.put("source", new String[]{candidate.getMatchedScopeName(), candidate.getMatchedPropertyName()});
        if (comparison != null && !comparison.isRootPropertyMovesDisabled()) {
            for (var move : comparison.getExistingRootPropertyMoveSnapshots()) {
                affected.put("root:"+move.settingName(), new String[]{null, move.settingName()});
                affected.put("moved:"+move.settingName(), new String[]{scope, move.settingName()});
            }
        }
        for (var path : affected.entrySet()) {
            String[] names = path.getValue(); String value = actual.getPropertyValue(names[0], names[1]);
            boolean changing = !path.getKey().equals("destination") || !Objects.equals(value, candidate.getProposedValue());
            changing |= candidate.getSuggestedOperation() == org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.MERGE;
            if (!changing) continue;
            if (candidate.getAnalysisJob().isSettingBookSource() && actual.isManuallyEdited(names[0], names[1])) return AutomaticReviewHoldReason.HUMAN_CURRENT_SETTING_PROTECTED;
            var history = repository.findAllByTargetWorldSettingIdAndReviewStatusOrderByReviewedAtDescCreatedAtDescIdDesc(actual.getId(), WorldSettingReviewStatus.CONFIRMED);
            var prior = history.stream()
                    .filter(row -> !row.isHistoryOnly() && samePath(row.getFinalScopeName(), row.getFinalSettingName(), names[0], names[1])).toList();
            if (value == null) {
                if (candidate.getAnalysisJob().isSettingBookSource() && !prior.isEmpty()) return AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
                continue;
            }
            var matching = org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingCurrentSources.forProperty(history, names[0], names[1], value);
            var times = matching.stream().map(row -> AnalysisCurrentSourceTimeMapper.from(row.getAnalysisJob(), row.getEffectiveFromEpisodeNo(), row.getTemporalScope())).toList();
            boolean destination = path.getKey().equals("destination");
            if (SettingBookTemporalPolicy.requiresReview(incoming, times,
                    !destination || candidate.getSuggestedOperation() != org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingSuggestedOperation.MERGE,
                    destination)) return AutomaticReviewHoldReason.SETTING_TIME_CONFIRMATION_REQUIRED;
        }
        return null;
    }

    private boolean samePath(String aScope, String aName, String bScope, String bName) {
        return Objects.equals(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer.duplicateKey(aScope), org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer.duplicateKey(bScope))
                && Objects.equals(org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer.duplicateKey(aName), org.monitoring.catchholebackend.domain.worldsetting.processor.WorldSettingNameNormalizer.duplicateKey(bName));
    }

    public Optional<List<WorldSetting.Property>> project(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> selections,
            WorldSetting actual
    ) {
        return Optional.ofNullable(projectWithReason(candidates, selections, actual).properties());
    }

    public record ProjectionResult(List<WorldSetting.Property> properties, AutomaticReviewHoldReason holdReason) {
        static ProjectionResult held(AutomaticReviewHoldReason reason) {
            return new ProjectionResult(null, reason);
        }
    }

    public ProjectionResult projectWithReason(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> selections,
            WorldSetting actual
    ) {
        return projectOrdered(candidates.stream().sorted(WorldSettingCandidateChronology.comparator()).toList(),
                selections, actual);
    }

    public ProjectionResult projectAutomatically(
            List<WorldSettingCandidate> candidates,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> selections,
            WorldSetting actual
    ) {
        if (candidates.stream().anyMatch(candidate -> candidate.getComparisonDecision() == null
                || candidate.getComparisonDecision().getBaseWorldSettingVersion() == null)) {
            return ProjectionResult.held(AutomaticReviewHoldReason.REVIEW_REQUIRED);
        }
        // 자동 반영은 비교가 의존한 이전 batch를 먼저 복원한다. 같은 version 안에서는
        // 기존 원문 순서를 유지하며, 수동 선택에는 이 정렬을 적용하지 않는다.
        Comparator<WorldSettingCandidate> comparisonOrder = Comparator
                .comparing((WorldSettingCandidate candidate) -> candidate.getComparisonDecision().getBaseWorldSettingVersion())
                .thenComparing(WorldSettingCandidateChronology.comparator());
        return projectOrdered(candidates.stream().sorted(comparisonOrder).toList(), selections, actual);
    }

    private ProjectionResult projectOrdered(
            List<WorldSettingCandidate> ordered,
            Map<UUID, WorldSettingCandidateGroupConfirmRequest.Decision> selections,
            WorldSetting actual
    ) {
        Set<String> targetRefs = new HashSet<>();
        for (WorldSettingCandidate candidate : ordered) {
            if ((candidate.getFinalOperation() != null)) {
                // 사용자에게 저장된 명시적 분류·대상·전체 경로 선택은 원래 AI identity보다 우선한다.
                continue;
            }
            var comparison = candidate.getComparisonDecision();
            if (comparison == null) {
                return ProjectionResult.held(AutomaticReviewHoldReason.REVIEW_REQUIRED);
            }
            String provisional = comparison.getProvisionalSubjectKey();
            UUID expectedActualId = comparison.getTargetWorldSetting() == null ? null
                    : comparison.getTargetWorldSetting().getId();
            if (provisional != null) {
                targetRefs.add(provisional);
                UUID anchorId = UUID.fromString(provisional.substring("provisional-world:".length()));
                var anchor = repository.findByIdAndWorkId(anchorId, candidate.getWork().getId()).orElse(null);
                if (anchor != null && anchor.getTargetWorldSetting() != null) {
                    expectedActualId = anchor.getTargetWorldSetting().getId();
                }
            } else {
                targetRefs.add("world:" + expectedActualId);
            }
            if (!Objects.equals(expectedActualId, actual == null ? null : actual.getId())) {
                return ProjectionResult.held(AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
            }
        }
        if (targetRefs.size() > 1 || targetRefs.isEmpty() && ordered.stream().anyMatch(candidate -> !(candidate.getFinalOperation() != null))) {
            return ProjectionResult.held(AutomaticReviewHoldReason.SUBJECT_CONFIRMATION_REQUIRED);
        }
        WorldSettingPropertyView projected = new WorldSettingPropertyView(actual == null
                ? JsonNodeFactory.instance.objectNode() : actual.getPropertiesJson());
        Set<UUID> appliedDecisions = new HashSet<>();
        try {
            for (WorldSettingCandidate candidate : ordered) {
                var selection = selections.get(candidate.getId());
                var comparison = candidate.getComparisonDecision();
                if (selection.operation() == WorldSettingOperation.EXCLUDE) {
                    continue;
                }
                boolean proposalUnchanged = !(candidate.getFinalOperation() != null) && comparison != null
                        && selection.operation().name().equals(comparison.getSuggestedOperation().name())
                        && Objects.equals(selection.scopeName(), comparison.getProposedScopeName())
                        && Objects.equals(selection.settingName(), comparison.getProposedSettingName())
                        && Objects.equals(selection.value(), comparison.getProposedValue());
                if (proposalUnchanged && !appliedDecisions.add(comparison.getId())) {
                    continue;
                }
                String currentValue = projected.value(selection.scopeName(), selection.settingName());
                if (projected.conflicts(selection.scopeName(), selection.settingName())) {
                    return ProjectionResult.held(AutomaticReviewHoldReason.SETTING_LOCATION_CONFLICT);
                }
                if (selection.operation() == WorldSettingOperation.ADD) {
                    if (currentValue != null && !Objects.equals(currentValue, selection.value())) {
                        return ProjectionResult.held(AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
                    }
                } else if (currentValue == null || proposalUnchanged
                        && !Objects.equals(currentValue, comparison.getBeforeValue())
                        && !Objects.equals(currentValue, selection.value())) {
                    return ProjectionResult.held(AutomaticReviewHoldReason.CURRENT_SETTING_CHANGED);
                }
                if (proposalUnchanged && !comparison.isRootPropertyMovesDisabled()) {
                    for (var move : comparison.getExistingRootPropertyMoveSnapshots()) {
                        projected.moveRoot(move.settingName(), selection.scopeName(), move.beforeValue());
                    }
                }
                projected.upsert(selection.scopeName(), selection.settingName(), selection.value());
            }
        } catch (AppException exception) {
            return ProjectionResult.held(AutomaticReviewHoldReason.SETTING_LOCATION_CONFLICT);
        }
        return new ProjectionResult(projected.properties(), null);
    }
}
