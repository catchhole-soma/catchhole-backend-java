package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisCandidateGateRow;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisCandidateDomain;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** Classifies current earlier-episode candidates without loading Job JSON. Caller holds the Work lock for closure. */
@Component
@RequiredArgsConstructor
public class AnalysisCandidateReviewGate {
    private static final Set<String> RECOVERY_STATUSES = Set.of("PENDING", "PROCESSING", "FAILED", "RECOMPARISON_REQUIRED");
    private static final Set<String> RECOVERY_HOLDS = Set.of("SUBJECT_RESOLUTION_FAILED", "COMPARISON_INPUT_TOO_LARGE", "CURRENT_SETTING_CHANGED");
    private static final Set<String> AMBIGUOUS_HOLDS = Set.of("HUMAN_CURRENT_SETTING_PROTECTED", "SETTING_TIME_CONFIRMATION_REQUIRED",
            "SUBJECT_CONFIRMATION_REQUIRED", "DEPENDENCY_CONFIRMATION_REQUIRED", "SETTING_VALUE_CONFIRMATION_REQUIRED",
            "SETTING_LOCATION_CONFLICT", "REVIEW_REQUIRED");
    private final SettingCandidateRepository characters;
    private final WorldSettingCandidateRepository worlds;

    public record Entry(AnalysisCandidateDomain domain, UUID id, UUID sourceJobId, LocalDateTime updatedAt, String revision) {}
    public record Snapshot(UUID workId, int beforeEpisodeNo, List<Entry> manualRequired,
                           List<Entry> ambiguous, List<Entry> recoveryRequired) {
        public Snapshot {
            manualRequired = List.copyOf(manualRequired);
            ambiguous = List.copyOf(ambiguous);
            recoveryRequired = List.copyOf(recoveryRequired);
        }
        public long manualCharacterCount() { return count(manualRequired, AnalysisCandidateDomain.CHARACTER); }
        public long manualWorldCount() { return count(manualRequired, AnalysisCandidateDomain.WORLD); }
        public long ambiguousCharacterCount() { return count(ambiguous, AnalysisCandidateDomain.CHARACTER); }
        public long ambiguousWorldCount() { return count(ambiguous, AnalysisCandidateDomain.WORLD); }
        private static long count(List<Entry> entries, AnalysisCandidateDomain domain) {
            return entries.stream().filter(entry -> entry.domain() == domain).count();
        }
    }

    public Snapshot snapshot(UUID workId, int beforeEpisodeNo) {
        List<Entry> manual = new ArrayList<>(), ambiguous = new ArrayList<>(), recovery = new ArrayList<>();
        classify(AnalysisCandidateDomain.CHARACTER, characters.findContinuationGateRows(workId, beforeEpisodeNo), manual, ambiguous, recovery);
        classify(AnalysisCandidateDomain.WORLD, worlds.findContinuationGateRows(workId, beforeEpisodeNo), manual, ambiguous, recovery);
        Comparator<Entry> order = Comparator.comparing(Entry::domain).thenComparing(entry -> entry.id().toString());
        manual.sort(order); ambiguous.sort(order); recovery.sort(order);
        return new Snapshot(workId, beforeEpisodeNo, manual, ambiguous, recovery);
    }

    public void closeAmbiguous(Snapshot expected, UUID requestId) {
        Objects.requireNonNull(requestId);
        if (!expected.equals(snapshot(expected.workId(), expected.beforeEpisodeNo()))) throw conflict();
        var characterIds = expected.ambiguous().stream().filter(entry -> entry.domain() == AnalysisCandidateDomain.CHARACTER).map(Entry::id).toList();
        var worldIds = expected.ambiguous().stream().filter(entry -> entry.domain() == AnalysisCandidateDomain.WORLD).map(Entry::id).toList();
        if (!characterIds.isEmpty()) {
            var candidates = characters.findContinuationCandidatesForUpdate(expected.workId(), characterIds);
            if (candidates.size() != characterIds.size()) throw conflict();
            candidates.forEach(candidate -> candidate.closeForContinuation(requestId));
        }
        if (!worldIds.isEmpty()) {
            var candidates = worlds.findContinuationCandidatesForUpdate(expected.workId(), worldIds);
            if (candidates.size() != worldIds.size()) throw conflict();
            candidates.forEach(candidate -> candidate.closeForContinuation(requestId));
        }
    }

    private void classify(AnalysisCandidateDomain domain, List<AnalysisCandidateGateRow> rows,
                          List<Entry> manual, List<Entry> ambiguous, List<Entry> recovery) {
        for (AnalysisCandidateGateRow row : rows) {
            Entry entry = new Entry(domain, row.getId(), row.getSourceJobId(), row.getUpdatedAt(), revision(row));
            // Manual work stays a blocking review obligation even while its comparison needs recovery.
            if (!"AUTOMATIC".equals(row.getReviewMode()) || row.getReviewApplicationHeldAt() != null
                    || Boolean.TRUE.equals(row.getUserModified()) || row.getReviewedApplicationMode() != null) {
                manual.add(entry);
            // Technical/in-progress automatic work is never ordinary ambiguity or silently closed.
            } else if (!"SUCCEEDED".equals(row.getJobStatus()) || RECOVERY_STATUSES.contains(Objects.toString(row.getComparisonStatus(), ""))
                    || RECOVERY_HOLDS.contains(Objects.toString(row.getHoldReason(), ""))
                    || "BATCH_LIMIT_EXCEEDED".equals(row.getComparisonReviewReason())
                    || "ORDERED_PROVISIONAL".equals(row.getAnalysisMode()) && !"SEALED".equals(row.getJournalStatus())) {
                recovery.add(entry);
            } else if ("ORDERED_PROVISIONAL".equals(row.getAnalysisMode()) && row.getAutomaticAppliedAt() != null
                    && normalAmbiguity(domain, row)) {
                ambiguous.add(entry);
            } else {
                // A leftover proposal without an identified ordinary hold must be reviewed, not discarded by inference.
                manual.add(entry);
            }
        }
    }

    private boolean normalAmbiguity(AnalysisCandidateDomain domain, AnalysisCandidateGateRow row) {
        if (row.getHoldReason() != null && !AMBIGUOUS_HOLDS.contains(row.getHoldReason())) return false;
        boolean held = AMBIGUOUS_HOLDS.contains(Objects.toString(row.getHoldReason(), ""))
                || "REVIEW_REQUIRED".equals(row.getSuggestedOperation());
        if (domain == AnalysisCandidateDomain.WORLD) {
            return "COMPLETED".equals(row.getComparisonStatus())
                    && (held || row.getComparisonReviewReason() != null || "CONFLICT".equals(row.getConsolidationStatus()));
        }
        return "COMPLETED".equals(row.getComparisonStatus()) && held
                || "AMBIGUOUS".equals(row.getMatchStatus())
                    && ("WAITING_FOR_CHARACTER_MATCH".equals(row.getComparisonStatus())
                        || "CHARACTER_DISCOVERY".equals(row.getCandidateKind()) && "NOT_REQUIRED".equals(row.getComparisonStatus()))
                || "CHARACTER_DISCOVERY".equals(row.getCandidateKind()) && "NOT_REQUIRED".equals(row.getComparisonStatus()) && held;
    }

    private String revision(AnalysisCandidateGateRow row) {
        return String.join("|", List.of(Objects.toString(row.getUpdatedAt(), ""), Objects.toString(row.getSourceUpdatedAt(), ""),
                Objects.toString(row.getReviewMode(), ""), Objects.toString(row.getJobStatus(), ""),
                Objects.toString(row.getAnalysisMode(), ""), Objects.toString(row.getJournalStatus(), ""),
                Objects.toString(row.getAutomaticAppliedAt(), ""), Objects.toString(row.getReviewApplicationHeldAt(), ""),
                Objects.toString(row.getComparisonStatus(), ""), Objects.toString(row.getSuggestedOperation(), ""),
                Objects.toString(row.getHoldReason(), ""), Objects.toString(row.getUserModified(), ""),
                Objects.toString(row.getReviewedApplicationMode(), ""), Objects.toString(row.getMatchStatus(), ""),
                Objects.toString(row.getCandidateKind(), ""), Objects.toString(row.getComparisonReviewReason(), ""),
                Objects.toString(row.getConsolidationStatus(), "")));
    }

    private AppException conflict() { return new AppException(AnalysisJobErrorCode.ANALYSIS_REVIEW_GATE_CHANGED); }
}
