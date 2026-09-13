package org.monitoring.catchholebackend.domain.worldsetting.service;

import org.monitoring.catchholebackend.domain.analysis.type.AutomaticReviewHoldReason;
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

/** 사용자 선택을 실제 대상과 순차 적용해 전체 검증한 뒤 한 번에 반영할 최종값을 만든다. */
@Component
@RequiredArgsConstructor
public class WorldSettingAnalysisConfirmation {

    private final WorldSettingCandidateRepository repository;

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
