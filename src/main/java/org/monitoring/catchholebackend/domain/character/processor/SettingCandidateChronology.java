package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;

/** 설정 후보를 회차와 원문 등장 순서에 따라 정렬한다. */
public final class SettingCandidateChronology {

    private static final Comparator<SettingCandidate> COMPARATOR = Comparator
            .comparing(
                    (SettingCandidate candidate) -> candidate.getEpisode() == null
                            ? null
                            : candidate.getEpisode().getEpisodeNo(),
                    Comparator.nullsLast(Comparator.naturalOrder())
            )
            // 같은 회차에서는 LLM 응답 배열이나 UUID 생성 순서보다 원문 등장 순서를 우선한다.
            .thenComparing(
                    SettingCandidateChronology::earliestEvidenceOffset,
                    Comparator.nullsLast(Comparator.naturalOrder())
            )
            .thenComparing(
                    SettingCandidate::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            )
            .thenComparing(SettingCandidate::getId);

    private SettingCandidateChronology() {
    }

    public static List<SettingCandidate> sorted(List<SettingCandidate> candidates) {
        return candidates.stream().sorted(COMPARATOR).toList();
    }

    private static Integer earliestEvidenceOffset(SettingCandidate candidate) {
        JsonNode evidenceSpans = candidate.getEvidenceSpans();
        if (evidenceSpans == null || !evidenceSpans.isArray()) {
            return null;
        }
        Integer earliest = null;
        for (JsonNode evidenceSpan : evidenceSpans) {
            JsonNode startOffset = evidenceSpan.get("startOffset");
            if (startOffset == null || !startOffset.isIntegralNumber()) {
                continue;
            }
            int value = startOffset.asInt();
            earliest = earliest == null ? value : Math.min(earliest, value);
        }
        return earliest;
    }
}
