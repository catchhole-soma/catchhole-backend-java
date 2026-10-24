package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCurrentSourceTimePayload;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind;

/** Same-owner frozen CURRENT is advisory; it supplies no comparison write references. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "같은 인물의 전체 고정 CURRENT와 출처 근거. 읽기 전용이며 P/Q 변경 권한을 추가하지 않습니다.")
public record WorkerCharacterAdvisoryCurrentContextPayload(
        int formatVersion, int captureVersion, UUID runId, long generation,
        String inputStateHash, String sourceHash, String projectionStateHash, boolean complete,
        @Schema(nullable = true) UUID characterId,
        @Schema(nullable = true) String provisionalSubjectKey,
        List<CurrentSlot> currentSlots,
        Object currentNarrativeTime,
        List<Object> historyFacts,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> selectionNames
) {
    public WorkerCharacterAdvisoryCurrentContextPayload {
        selectionNames = selectionNames == null ? List.of() : List.copyOf(selectionNames);
    }
    public WorkerCharacterAdvisoryCurrentContextPayload(int formatVersion, int captureVersion, UUID runId, long generation,
            String inputStateHash, String sourceHash, String projectionStateHash, boolean complete, UUID characterId,
            String provisionalSubjectKey, List<CurrentSlot> currentSlots, Object currentNarrativeTime, List<Object> historyFacts) {
        this(formatVersion, captureVersion, runId, generation, inputStateHash, sourceHash, projectionStateHash, complete,
                characterId, provisionalSubjectKey, currentSlots, currentNarrativeTime, historyFacts, List.of());
    }
    public WorkerCharacterAdvisoryCurrentContextPayload withSelectionNames(List<String> names) {
        return new WorkerCharacterAdvisoryCurrentContextPayload(formatVersion, captureVersion, runId, generation,
                inputStateHash, sourceHash, projectionStateHash, complete, characterId, provisionalSubjectKey,
                currentSlots, currentNarrativeTime, historyFacts, names);
    }
    public WorkerCharacterAdvisoryCurrentContextPayload(int formatVersion, int captureVersion, UUID runId, long generation,
            String inputStateHash, String sourceHash, String projectionStateHash, boolean complete, UUID characterId,
            String provisionalSubjectKey, List<CurrentSlot> currentSlots) {
        this(formatVersion, captureVersion, runId, generation, inputStateHash, sourceHash, projectionStateHash, complete,
                characterId, provisionalSubjectKey, currentSlots, null, List.of());
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerCharacterAdvisoryCurrentSlotPayload")
    public record CurrentSlot(CharacterFactType factType, String factKey,
            @Schema(nullable = true) String factValue,
            @Schema(types = {"object", "array", "string", "number", "boolean", "null"}) Object valueJson,
            boolean currentValueProtected, List<WorkerAnalysisCurrentSourceTimePayload> currentSourceTimes,
            List<SupportingOrigin> supportingSources) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerCurrentSupportingOriginPayload")
    public record SupportingOrigin(@Schema(nullable = true) UUID sourceCandidateId,
            @Schema(nullable = true) UUID sourceAnalysisJobId, String availability,
            @Schema(nullable = true) String unavailableReason,
            @Schema(nullable = true) Boolean candidateWasEdited,
            @Schema(nullable = true) SupportingSource source, List<EvidenceSpan> evidenceSpans,
            String quoteRelation, Object narrativeTime) {
        public SupportingOrigin(UUID sourceCandidateId, UUID sourceAnalysisJobId, String availability, String unavailableReason,
                Boolean candidateWasEdited, SupportingSource source, List<EvidenceSpan> evidenceSpans, String quoteRelation) {
            this(sourceCandidateId, sourceAnalysisJobId, availability, unavailableReason, candidateWasEdited, source, evidenceSpans, quoteRelation, null);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerCurrentSupportingSourcePayload")
    public record SupportingSource(AnalysisSourceKind sourceKind,
            @Schema(nullable = true) UUID episodeId, @Schema(nullable = true) Integer episodeNo,
            @Schema(nullable = true) UUID settingBookId, @Schema(nullable = true) UUID revisionId,
            @Schema(nullable = true) Integer revisionNo, String contentHash,
            @Schema(nullable = true) SettingBookReferenceTimeKind referenceTimeKind,
            @Schema(nullable = true) Integer referenceEpisodeNo) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "WorkerCurrentSupportingEvidenceSpan")
    public record EvidenceSpan(String quote, @Schema(nullable = true) Integer startOffset,
            @Schema(nullable = true) Integer endOffset) {}
}
