package org.monitoring.catchholebackend.domain.worldsetting.processor;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.type.WorldSettingOperation;

/** 값 변경은 출처를 갱신하고, 실제 적용된 root 이동은 기존 값의 출처를 함께 옮긴다. */
public final class WorldSettingCurrentSources {
    private WorldSettingCurrentSources() {}

    public static List<WorldSettingCandidate> forProperty(List<WorldSettingCandidate> history,
            String scope, String name, String value) {
        List<WorldSettingCandidate> applied = history.stream().filter(row -> !row.isHistoryOnly())
                .filter(row -> row.getFinalOperation() != WorldSettingOperation.EXCLUDE)
                .sorted(Comparator.comparing(WorldSettingCandidate::getAppliedWorldSettingVersion, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(row -> row.getComparisonDecision() == null ? null : row.getComparisonDecision().getBaseWorldSettingVersion(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(WorldSettingCandidate::getReviewedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(WorldSettingCandidate::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(WorldSettingCandidate::getId)).toList();
        var applications = new LinkedHashMap<Application, List<WorldSettingCandidate>>();
        for (var row : applied) {
            var key = new Application(row.getComparisonDecision() == null ? row.getId() : row.getComparisonDecision().getId(),
                    row.getAppliedWorldSettingVersion(), Path.of(row.getFinalScopeName(), row.getFinalSettingName()),
                    row.getFinalOperation(), row.getFinalValue());
            applications.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        var current = new LinkedHashMap<Path, Current>();
        var movedDecisions = new HashSet<Move>();
        applications.forEach((application, rows) -> {
            var decision = rows.getFirst().getComparisonDecision();
            if (decision != null && application.path().scope() != null
                    && decision.getRootPropertyMovesAppliedWorldSettingVersion() != null
                    && Objects.equals(decision.getRootPropertyMovesAppliedWorldSettingVersion(), application.version())
                    && movedDecisions.add(new Move(decision.getId(), application.version()))) {
                for (var snapshot : decision.getExistingRootPropertyMoveSnapshots()) {
                    var previous = current.remove(Path.of(null, snapshot.settingName()));
                    var sources = previous != null && Objects.equals(previous.value(), snapshot.beforeValue())
                            ? previous.sources() : List.<WorldSettingCandidate>of();
                    current.put(Path.of(rows.getFirst().getFinalScopeName(), snapshot.settingName()),
                            new Current(snapshot.beforeValue(), sources));
                }
            }
            var sources = new ArrayList<WorldSettingCandidate>();
            var previous = current.get(application.path());
            if (application.operation() == WorldSettingOperation.MERGE && previous != null) sources.addAll(previous.sources());
            sources.addAll(rows);
            current.put(application.path(), new Current(application.value(), List.copyOf(sources)));
        });
        var result = current.get(Path.of(scope, name));
        return result != null && Objects.equals(result.value(), value) ? result.sources() : List.of();
    }

    private record Application(java.util.UUID decisionId, Long version, Path path, WorldSettingOperation operation, String value) {}
    private record Move(java.util.UUID decisionId, Long version) {}
    private record Current(String value, List<WorldSettingCandidate> sources) {}
    private record Path(String scope, String name) {
        private static Path of(String scope, String name) {
            return new Path(WorldSettingNameNormalizer.duplicateKey(scope), WorldSettingNameNormalizer.duplicateKey(name));
        }
    }

    public static boolean samePath(String leftScope, String leftName, String rightScope, String rightName) {
        return Objects.equals(WorldSettingNameNormalizer.duplicateKey(leftScope), WorldSettingNameNormalizer.duplicateKey(rightScope))
                && Objects.equals(WorldSettingNameNormalizer.duplicateKey(leftName), WorldSettingNameNormalizer.duplicateKey(rightName));
    }
}
