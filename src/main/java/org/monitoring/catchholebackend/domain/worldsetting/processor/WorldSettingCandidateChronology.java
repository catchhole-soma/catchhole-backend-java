package org.monitoring.catchholebackend.domain.worldsetting.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;

/** 같은 회차의 근거 위치도 유지해 정리 결정과 최종 적용 순서를 일치시킨다. */
public final class WorldSettingCandidateChronology {
    private WorldSettingCandidateChronology() {
    }

    public static Comparator<WorldSettingCandidate> comparator() {
        return Comparator.comparing((WorldSettingCandidate candidate) -> candidate.getSourceEpisode().getEpisodeNo())
                .thenComparingInt(WorldSettingCandidateChronology::firstEvidenceOffset)
                .thenComparing(WorldSettingCandidate::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(WorldSettingCandidate::getId);
    }

    private static int firstEvidenceOffset(WorldSettingCandidate candidate) {
        int first = Integer.MAX_VALUE;
        JsonNode spans = candidate.getEvidenceSpans();
        if (spans != null && spans.isArray()) {
            for (JsonNode span : spans) {
                if (span.path("startOffset").isIntegralNumber() && span.path("startOffset").asInt() >= 0) {
                    first = Math.min(first, span.path("startOffset").asInt());
                }
            }
        }
        return first;
    }
}
