package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateSnapshotChangeResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotEntry;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSourceManager;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactOperation;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.character.type.CharacterSnapshotAction;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class SettingCandidateMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CharacterSnapshotAccessor snapshotAccessor;
    private final CharacterSnapshotSourceManager snapshotSourceManager;

    public SettingCandidateMapper(
            CharacterSnapshotAccessor snapshotAccessor,
            CharacterSnapshotSourceManager snapshotSourceManager
    ) {
        this.snapshotAccessor = snapshotAccessor;
        this.snapshotSourceManager = snapshotSourceManager;
    }

    public SettingCandidateResponse toResponse(
            SettingCandidate candidate,
            boolean attributeNameEditable,
            String attributeNamePrefix
    ) {
        return toResponse(candidate, attributeNameEditable, attributeNamePrefix, true);
    }

    /** 목록에서는 화면에 사용하지 않는 원본 AI payload를 제외해 응답 직렬화와 브라우저 파싱 비용을 줄인다. */
    public SettingCandidateResponse toReviewListResponse(
            SettingCandidate candidate,
            boolean attributeNameEditable,
            String attributeNamePrefix
    ) {
        return toResponse(candidate, attributeNameEditable, attributeNamePrefix, false);
    }

    private SettingCandidateResponse toResponse(
            SettingCandidate candidate,
            boolean attributeNameEditable,
            String attributeNamePrefix,
            boolean includeRawAiResult
    ) {
        Episode episode = candidate.getEpisode();
        AnalysisJob analysisJob = candidate.getAnalysisJob();

        return new SettingCandidateResponse(
                candidate.getId(),
                candidate.getWork().getId(),
                episode == null ? null : episode.getId(),
                episode == null ? null : episode.getEpisodeNo(),
                candidate.getSourceChunkId(),
                analysisJob == null ? null : analysisJob.getId(),
                candidate.getCandidateKind(),
                candidate.getEntityType(),
                candidate.getEntityName(),
                candidate.getRawEntityMention(),
                candidate.getMatchedCharacterId(),
                candidate.getMatchStatus(),
                candidate.getAttributeName(),
                attributeNameEditable,
                attributeNamePrefix,
                candidate.getAttributeValue(),
                candidate.getValueType(),
                toJsonValue(candidate.getValueJson()),
                toJsonValue(candidate.getEvidenceSpans()),
                candidate.getConfidence(),
                candidate.getReviewStatus(),
                includeRawAiResult ? toJsonValue(candidate.getRawAiResultJson()) : null,
                candidate.getComparisonStatus(),
                candidate.getSuggestedOperation(),
                candidate.getTemporalScope(),
                candidate.getComparisonTargetFactType(),
                candidate.getComparisonTargetFactKey(),
                toJsonValue(candidate.getProposedValueJson()),
                candidate.getProposedFactValue(),
                toSnapshotChanges(candidate),
                candidate.getComparisonReason(),
                candidate.getComparisonErrorMessage(),
                candidate.getComparisonBaseSnapshotVersion(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }

    private List<SettingCandidateSnapshotChangeResponse> toSnapshotChanges(SettingCandidate candidate) {
        CharacterFactOperation operation = candidate.getSuggestedOperation();
        JsonNode removals = candidate.getRemovedSnapshotEntriesJson();
        boolean hasUpsert = operation == CharacterFactOperation.ADD
                || operation == CharacterFactOperation.UPDATE
                || operation == CharacterFactOperation.MERGE;
        boolean removesTarget = operation == CharacterFactOperation.REMOVE;
        if (!hasUpsert && !removesTarget
                && (removals == null || !removals.isArray() || removals.isEmpty())) {
            return List.of();
        }
        Map<CharacterSnapshotSlot, CharacterSnapshotEntry> snapshot = currentSnapshot(candidate);
        List<SettingCandidateSnapshotChangeResponse> changes = new ArrayList<>();
        if (hasUpsert
                && candidate.getComparisonTargetFactType() != null
                && candidate.getComparisonTargetFactKey() != null) {
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                    candidate.getComparisonTargetFactType(),
                    candidate.getComparisonTargetFactKey()
            );
            CharacterSnapshotEntry before = snapshot.get(slot);
            changes.add(new SettingCandidateSnapshotChangeResponse(
                    CharacterSnapshotAction.UPSERT,
                    slot.factType(),
                    slot.factKey(),
                    before == null ? null : before.factValue(),
                    before == null ? null : toJsonValue(before.valueJson()),
                    candidate.getProposedFactValue(),
                    toJsonValue(candidate.getProposedValueJson())
            ));
        }
        if (removesTarget
                && candidate.getComparisonTargetFactType() != null
                && candidate.getComparisonTargetFactKey() != null) {
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                    candidate.getComparisonTargetFactType(),
                    candidate.getComparisonTargetFactKey()
            );
            CharacterSnapshotEntry before = snapshot.get(slot);
            changes.add(new SettingCandidateSnapshotChangeResponse(
                    CharacterSnapshotAction.REMOVE,
                    slot.factType(),
                    slot.factKey(),
                    before == null ? null : before.factValue(),
                    before == null ? null : toJsonValue(before.valueJson()),
                    null,
                    null
            ));
        }
        if (removals != null && removals.isArray()) {
            for (JsonNode removal : removals) {
                try {
                    CharacterSnapshotSlot slot = new CharacterSnapshotSlot(
                            CharacterFactType.valueOf(removal.path("factType").asText()),
                            removal.path("factKey").asText()
                    );
                    CharacterSnapshotEntry before = snapshot.get(slot);
                    changes.add(new SettingCandidateSnapshotChangeResponse(
                            CharacterSnapshotAction.REMOVE,
                            slot.factType(),
                            slot.factKey(),
                            before == null ? null : before.factValue(),
                            before == null ? null : toJsonValue(before.valueJson()),
                            null,
                            null
                    ));
                } catch (IllegalArgumentException ignored) {
                    // 과거 비정상 원본 비교 JSON은 응답 전체를 깨뜨리지 않고 변경 미리보기에서만 제외한다.
                }
            }
        }
        return List.copyOf(changes);
    }

    private Map<CharacterSnapshotSlot, CharacterSnapshotEntry> currentSnapshot(SettingCandidate candidate) {
        WorkCharacter character = candidate.getMatchedCharacter();
        return character == null
                ? Map.of()
                : snapshotAccessor.read(character, snapshotSourceManager.findSourceFactsBySlot(character));
    }

    public SettingCandidateReviewStatusResponse toReviewStatusResponse(SettingCandidate candidate) {
        return new SettingCandidateReviewStatusResponse(
                candidate.getId(),
                candidate.getReviewStatus()
        );
    }

    private Object toJsonValue(JsonNode jsonNode) {
        if (jsonNode == null) {
            return null;
        }
        return objectMapper.convertValue(jsonNode, Object.class);
    }
}
