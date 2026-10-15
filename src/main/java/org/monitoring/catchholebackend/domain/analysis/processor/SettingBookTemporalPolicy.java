package org.monitoring.catchholebackend.domain.analysis.processor;

import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCurrentSourceTimePayload;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactTemporalScope;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

/** Uses frozen narrative times; upload order and registered maximum episode are irrelevant. */
public final class SettingBookTemporalPolicy {
    private SettingBookTemporalPolicy() {}

    public static boolean requiresReview(WorkerAnalysisCurrentSourceTimePayload incoming,
            List<WorkerAnalysisCurrentSourceTimePayload> current, boolean changesCurrent) {
        return requiresReview(incoming, current, changesCurrent, false);
    }

    /** Validated automatic operations may omit an optional BOOK position; manual history stays strict. */
    public static boolean requiresReview(WorkerAnalysisCurrentSourceTimePayload incoming,
            List<WorkerAnalysisCurrentSourceTimePayload> current, boolean changesCurrent, boolean automaticDecision) {
        boolean book = incoming.sourceKind() == AnalysisSourceKind.SETTING_BOOK;
        if (book && (incoming.temporalScope() != CharacterFactTemporalScope.PRESENT
                || conflictsWithAnchor(incoming))) return true;
        // The automatic non-replacement call is only for an existing MERGE destination.
        // ADD/incoming-only probes use the strict overload or skip an absent destination.
        if (!changesCurrent) return automaticDecision && (current.isEmpty() || current.stream().anyMatch(time ->
                position(time) == null && !optionalBookPosition(time, true)));
        if (!book && current.stream().noneMatch(time -> time.sourceKind() == AnalysisSourceKind.SETTING_BOOK)) return false;
        Integer incomingPosition = position(incoming);
        if (current.isEmpty() || incomingPosition == null && !optionalBookPosition(incoming, automaticDecision)) return true;
        return current.stream().anyMatch(time -> position(time) == null
                ? !optionalBookPosition(time, automaticDecision)
                : incomingPosition != null && position(time) >= incomingPosition);
    }

    private static boolean optionalBookPosition(WorkerAnalysisCurrentSourceTimePayload time, boolean automaticDecision) {
        return automaticDecision && time.sourceKind() == AnalysisSourceKind.SETTING_BOOK
                && time.temporalScope() == CharacterFactTemporalScope.PRESENT
                && time.referenceTimeKind() == SettingBookReferenceTimeKind.UNSPECIFIED;
    }

    private static boolean conflictsWithAnchor(WorkerAnalysisCurrentSourceTimePayload time) {
        if (time.effectiveFromEpisodeNo() == null) return false;
        return time.referenceTimeKind() == SettingBookReferenceTimeKind.WORK_START
                || time.referenceTimeKind() == SettingBookReferenceTimeKind.EPISODE
                    && time.effectiveFromEpisodeNo() > time.referenceEpisodeNo();
    }

    private static Integer position(WorkerAnalysisCurrentSourceTimePayload time) {
        if (time.temporalScope() != CharacterFactTemporalScope.PRESENT) return null;
        if (time.sourceKind() == AnalysisSourceKind.EPISODE) return time.sourceEpisodeNo();
        if (time.sourceKind() != AnalysisSourceKind.SETTING_BOOK) return null;
        if (time.referenceTimeKind() == SettingBookReferenceTimeKind.WORK_START) return 0;
        return time.referenceTimeKind() == SettingBookReferenceTimeKind.EPISODE ? time.referenceEpisodeNo() : time.effectiveFromEpisodeNo();
    }
}
