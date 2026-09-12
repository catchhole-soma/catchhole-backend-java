package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;

/** 설정 후보를 회차와 원문 등장 순서에 따라 정렬한다. */
public final class SettingCandidateChronology {

    private static final Comparator<SettingCandidate> ASSIGNED_COMPARATOR = Comparator
            .comparing((SettingCandidate candidate) -> candidate.getEpisode() == null
                            ? null : candidate.getEpisode().getEpisodeNo(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(candidate -> candidate.getCharacterComparisonBatch().getCreatedAt(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(candidate -> candidate.getCharacterComparisonBatch().getId())
            .thenComparing(SettingCandidateChronology::assignedReferenceOrder,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SettingCandidate::getId);

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
        List<SettingCandidate> chronological = new ArrayList<>(candidates.stream().sorted(COMPARATOR).toList());
        // 비교 입력에 한 번 부여한 순서는 이후 문맥 재구성과 수동 확정에서도 불변이다.
        // 아직 배정되지 않은 후보의 원문상 위치는 유지하고 배정된 후보끼리만 그 순서를 복원한다.
        List<SettingCandidate> assigned = chronological.stream()
                .filter(candidate -> candidate.getCharacterComparisonBatch() != null)
                .sorted(ASSIGNED_COMPARATOR).toList();
        int assignedIndex = 0;
        for (int index = 0; index < chronological.size(); index++) {
            if (chronological.get(index).getCharacterComparisonBatch() != null) {
                chronological.set(index, assigned.get(assignedIndex++));
            }
        }
        return List.copyOf(chronological);
    }

    private static BigInteger assignedReferenceOrder(SettingCandidate candidate) {
        String reference = candidate.getCharacterComparisonCandidateRef();
        return reference != null && reference.matches("C[1-9][0-9]*") && reference.length() <= 20
                ? new BigInteger(reference.substring(1)) : null;
    }

    private static Integer earliestEvidenceOffset(SettingCandidate candidate) {
        JsonNode evidenceSpans = candidate.getEvidenceSpans();
        if (evidenceSpans == null || !evidenceSpans.isArray()) {
            return null;
        }
        Integer earliest = null;
        for (JsonNode evidenceSpan : evidenceSpans) {
            // Python 저장 형식이 기본이며 과거 Java 형식의 근거도 같은 순서로 읽는다.
            JsonNode startOffset = evidenceSpan.get("start_offset");
            if (startOffset == null || !startOffset.isIntegralNumber()) {
                startOffset = evidenceSpan.get("startOffset");
            }
            if (startOffset == null || !startOffset.isIntegralNumber()) {
                continue;
            }
            int value = startOffset.asInt();
            earliest = earliest == null ? value : Math.min(earliest, value);
        }
        return earliest;
    }
}
