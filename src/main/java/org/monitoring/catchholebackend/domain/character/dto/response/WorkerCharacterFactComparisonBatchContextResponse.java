package org.monitoring.catchholebackend.domain.character.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactSnapshotOrigin;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

@Schema(description = "Worker 캐릭터 Fact 묶음 비교 문맥")
public record WorkerCharacterFactComparisonBatchContextResponse(
        UUID comparisonBatchId,
        String characterRef,
        String matchedCharacterName,
        CharacterFactType canonicalFactType,
        long baseSnapshotVersion,
        List<WorkerCharacterFactComparisonBatchPayload.Candidate> candidates,
        List<SnapshotEntry> snapshotEntries,
        String contextToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String provisionalSubjectKey,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<WorkerCharacterProtectedSlotPayload> protectedSlots,
        @JsonInclude(JsonInclude.Include.NON_NULL) WorkerCharacterAdvisoryCurrentContextPayload advisoryCurrentContext
) {

    public WorkerCharacterFactComparisonBatchContextResponse(UUID comparisonBatchId, String characterRef, String matchedCharacterName,
            CharacterFactType canonicalFactType, long baseSnapshotVersion,
            List<WorkerCharacterFactComparisonBatchPayload.Candidate> candidates, List<SnapshotEntry> snapshotEntries,
            String contextToken, String provisionalSubjectKey,
            org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext,
            List<WorkerCharacterProtectedSlotPayload> protectedSlots) {
        this(comparisonBatchId, characterRef, matchedCharacterName, canonicalFactType, baseSnapshotVersion,
                candidates, snapshotEntries, contextToken, provisionalSubjectKey, analysisContext, protectedSlots, null);
    }

    public WorkerCharacterFactComparisonBatchContextResponse(UUID comparisonBatchId, String characterRef, String matchedCharacterName,
            CharacterFactType canonicalFactType, long baseSnapshotVersion,
            List<WorkerCharacterFactComparisonBatchPayload.Candidate> candidates, List<SnapshotEntry> snapshotEntries,
            String contextToken, String provisionalSubjectKey,
            org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload analysisContext) {
        this(comparisonBatchId, characterRef, matchedCharacterName, canonicalFactType, baseSnapshotVersion,
                candidates, snapshotEntries, contextToken, provisionalSubjectKey, analysisContext, List.of());
    }
    public WorkerCharacterFactComparisonBatchContextResponse(
            UUID comparisonBatchId, String characterRef, String matchedCharacterName,
            CharacterFactType canonicalFactType, long baseSnapshotVersion,
            List<WorkerCharacterFactComparisonBatchPayload.Candidate> candidates,
            List<SnapshotEntry> snapshotEntries, String contextToken
    ) {
        this(comparisonBatchId, characterRef, matchedCharacterName, canonicalFactType,
                baseSnapshotVersion, candidates, snapshotEntries, contextToken, null, null, List.of());
    }

    public record SnapshotEntry(
            String snapshotRef,
            CharacterFactSnapshotOrigin origin,
            @Schema(nullable = true) String sourceCandidateRef,
            List<String> dependencyCandidateRefs,
            CharacterFactType factType,
            String factKey,
            @Schema(nullable = true) String factValue,
            @Schema(nullable = true, implementation = JsonNode.class) Object valueJson,
            @JsonInclude(JsonInclude.Include.NON_NULL) Provenance provenance
    ) {
        public SnapshotEntry(
                String snapshotRef, CharacterFactSnapshotOrigin origin, String sourceCandidateRef,
                List<String> dependencyCandidateRefs, CharacterFactType factType, String factKey,
                String factValue, Object valueJson
        ) {
            this(snapshotRef, origin, sourceCandidateRef, dependencyCandidateRefs, factType, factKey,
                    factValue, valueJson, null);
        }
    }

    public record Provenance(String confirmationStatus, Integer sourceEpisodeNo,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reviewSource,
        @Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind sourceKind,
        @Schema(nullable = true) java.util.UUID sourceSettingBookId,
        @Schema(nullable = true) String sourceSettingBookName,
        @Schema(nullable = true) java.util.UUID sourceSettingBookRevisionId,
        @Schema(nullable = true) Integer sourceSettingBookRevisionNo,
        @Schema(nullable = true) String sourceHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) org.monitoring.catchholebackend.domain.upload.type.SettingBookReferenceTimeKind sourceReferenceTimeKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer sourceReferenceEpisodeNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer effectiveFromEpisodeNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean currentValueProtected,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCurrentSourceTimePayload> currentSourceTimes
) {
    public Provenance(String confirmationStatus, Integer sourceEpisodeNo, String reviewSource,
            org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind sourceKind, UUID sourceSettingBookId,
            String sourceSettingBookName, UUID sourceSettingBookRevisionId, Integer sourceSettingBookRevisionNo, String sourceHash) {
        this(confirmationStatus, sourceEpisodeNo, reviewSource, sourceKind, sourceSettingBookId, sourceSettingBookName,
                sourceSettingBookRevisionId, sourceSettingBookRevisionNo, sourceHash, null, null, null, null, List.of());
    }
    public Provenance(String confirmationStatus, Integer sourceEpisodeNo, String reviewSource) {
        this(confirmationStatus, sourceEpisodeNo, reviewSource, null, null, null, null, null, null);
    }

        public Provenance(String confirmationStatus, Integer sourceEpisodeNo) {
            this(confirmationStatus, sourceEpisodeNo, null, null, null, null, null, null, null);
        }
    }
}
