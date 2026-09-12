package org.monitoring.catchholebackend.domain.analysis.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCharacterSettingSchemaPayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisEpisodePayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisKnownCharacterPayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisProvenancePayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisProvisionalCharacterPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisJobWorkerMapper {

    private final CharacterSnapshotAccessor snapshotAccessor;
    private final AnalysisRunContextMapper analysisRunContextMapper;
    private final CharacterFactEvidenceMapper characterFactEvidenceMapper;

    public WorkerAnalysisJobPayload toResponse(
            AnalysisJob analysisJob,
            Episode episode,
            List<CharacterSettingSchema> characterSettingSchemas,
            List<WorkCharacter> knownCharacters,
            List<CharacterSnapshotSource> activeStatusSources
    ) {
        return toResponse(analysisJob, episode, characterSettingSchemas, knownCharacters, activeStatusSources, null);
    }

    public WorkerAnalysisJobPayload toResponse(
            AnalysisJob analysisJob,
            Episode episode,
            List<CharacterSettingSchema> characterSettingSchemas,
            List<WorkCharacter> knownCharacters,
            List<CharacterSnapshotSource> activeStatusSources,
            JsonNode orderedInputState
    ) {
        if (analysisJob.isOrderedProvisional() && (orderedInputState == null || !orderedInputState.isObject())) {
            throw new IllegalArgumentException("누적 모드의 고정 입력 상태가 필요합니다.");
        }
        Map<UUID, Map<CharacterSnapshotSlot, List<CharacterFact>>> statusSourceFactsByCharacter =
                groupStatusSourceFactsByCharacter(activeStatusSources);
        return new WorkerAnalysisJobPayload(
                analysisJob.getId(),
                analysisJob.getJobType(),
                analysisJob.getWork().getId(),
                analysisJob.getWork().getTitle(),
                analysisJob.getBatch() == null ? null : analysisJob.getBatch().getId(),
                analysisJob.getModelName(),
                analysisJob.getCurrentStep(),
                analysisJob.getLeaseToken(),
                analysisJob.getLeaseExpiresAt(),
                analysisJob.getClaimAttemptCount(),
                analysisJob.getCheckpointStage(),
                analysisJob.getWorldSettingCandidate() == null
                        ? null
                        : analysisJob.getWorldSettingCandidate().getId(),
                analysisJob.getSettingCandidate() == null
                        ? null
                        : analysisJob.getSettingCandidate().getId(),
                characterSettingSchemas.stream()
                        .map(this::toCharacterSettingSchemaResponse)
                        .toList(),
                analysisJob.isOrderedProvisional() ? toOrderedKnownCharacters(orderedInputState) : knownCharacters.stream()
                        .map(character -> toKnownCharacterResponse(
                                character,
                                statusSourceFactsByCharacter.getOrDefault(character.getId(), Map.of())
                        ))
                        .toList(),
                toEpisodeResponse(episode),
                analysisJob.isOrderedProvisional() ? analysisJob.getAnalysisMode() : null,
                analysisRunContextMapper.toResponse(analysisJob),
                analysisJob.isOrderedProvisional() ? toProvisionalCharacters(orderedInputState) : List.of(),
                analysisJob.isAutomaticReview() ? analysisJob.getReviewMode() : null
        );
    }

    private List<WorkerAnalysisKnownCharacterPayload> toOrderedKnownCharacters(JsonNode state) {
        List<WorkerAnalysisKnownCharacterPayload> result = new ArrayList<>();
        for (JsonNode character : state.path("characters")) {
            if (character.path("actualCharacterId").isTextual()) {
                result.add(new WorkerAnalysisKnownCharacterPayload(UUID.fromString(character.path("actualCharacterId").asText()),
                        character.path("name").asText(), toOrderedActiveStatuses(character),
                        toProvenance(character.path("provenance"), "CONFIRMED"),
                        toAliases(character.path("aliases")),
                        characterFactEvidenceMapper.toEvidenceSpans(character.path("identityEvidence"))));
            }
        }
        return List.copyOf(result);
    }

    private List<WorkerAnalysisProvisionalCharacterPayload> toProvisionalCharacters(JsonNode state) {
        List<WorkerAnalysisProvisionalCharacterPayload> result = new ArrayList<>();
        for (JsonNode character : state.path("characters")) {
            if (character.path("provisionalSubjectKey").isTextual()) {
                result.add(new WorkerAnalysisProvisionalCharacterPayload(character.path("provisionalSubjectKey").asText(),
                        character.path("name").asText(), toAliases(character.path("aliases")),
                        character.path("provenance").path("sourceEpisodeNo").asInt(), toOrderedActiveStatuses(character),
                        characterFactEvidenceMapper.toEvidenceSpans(character.path("identityEvidence"))));
            }
        }
        return List.copyOf(result);
    }

    private List<WorkerAnalysisKnownCharacterPayload.ActiveStatus> toOrderedActiveStatuses(JsonNode character) {
        List<WorkerAnalysisKnownCharacterPayload.ActiveStatus> result = new ArrayList<>();
        for (JsonNode slot : character.path("slots")) {
            if (slot.path("factType").asText().equals("STATUS") && !isExplicitlyInactive(slot.path("valueJson"))) {
                JsonNode provenance = slot.path("provenance");
                result.add(new WorkerAnalysisKnownCharacterPayload.ActiveStatus(slot.path("factKey").asText(),
                        slot.path("factValue").isNull() ? null : slot.path("factValue").asText(),
                        toProvenance(provenance, "CONFIRMED")));
            }
        }
        result.sort(java.util.Comparator.comparing(WorkerAnalysisKnownCharacterPayload.ActiveStatus::factKey));
        return List.copyOf(result);
    }

    private WorkerAnalysisCharacterSettingSchemaPayload toCharacterSettingSchemaResponse(
            CharacterSettingSchema settingSchema
    ) {
        return new WorkerAnalysisCharacterSettingSchemaPayload(
                settingSchema.getSchemaKey(),
                settingSchema.getDisplayName(),
                settingSchema.getAttributePattern(),
                toAliases(settingSchema.getAliasesJson()),
                settingSchema.getValueType()
        );
    }

    private List<String> toAliases(JsonNode aliasesJson) {
        if (aliasesJson == null || !aliasesJson.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(aliasesJson.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .toList();
    }

    private WorkerAnalysisProvenancePayload toProvenance(JsonNode source, String fallbackStatus) {
        List<UUID> ids = new ArrayList<>();
        source.path("sourceCandidateIds").forEach(id -> ids.add(UUID.fromString(id.asText())));
        return new WorkerAnalysisProvenancePayload(source.path("confirmationStatus").asText(fallbackStatus),
                source.path("sourceEpisodeNo").isIntegralNumber() ? source.path("sourceEpisodeNo").asInt() : null,
                List.copyOf(ids), source.path("reviewSource").isTextual() ? source.path("reviewSource").asText() : null);
    }

    private WorkerAnalysisKnownCharacterPayload toKnownCharacterResponse(
            WorkCharacter character,
            Map<CharacterSnapshotSlot, List<CharacterFact>> statusSourceFactsBySlot
    ) {
        return new WorkerAnalysisKnownCharacterPayload(
                character.getId(),
                character.getName(),
                snapshotAccessor.read(character, statusSourceFactsBySlot).values().stream()
                        .filter(entry -> entry.slot().factType() == CharacterFactType.STATUS)
                        .filter(entry -> !isExplicitlyInactive(entry.valueJson()))
                        .sorted(java.util.Comparator.comparing(entry -> entry.slot().factKey()))
                        .map(entry -> new WorkerAnalysisKnownCharacterPayload.ActiveStatus(
                                entry.slot().factKey(),
                                entry.factValue()
                        ))
                        .toList()
        );
    }

    private boolean isExplicitlyInactive(JsonNode valueJson) {
        JsonNode active = valueJson == null || !valueJson.isObject() ? null : valueJson.get("active");
        return active != null && active.isBoolean() && !active.booleanValue();
    }

    private Map<UUID, Map<CharacterSnapshotSlot, List<CharacterFact>>> groupStatusSourceFactsByCharacter(
            List<CharacterSnapshotSource> sources
    ) {
        Map<UUID, Map<CharacterSnapshotSlot, List<CharacterFact>>> grouped = new LinkedHashMap<>();
        for (CharacterSnapshotSource source : sources) {
            UUID characterId = source.getWorkCharacter().getId();
            CharacterSnapshotSlot slot = new CharacterSnapshotSlot(source.getFactType(), source.getFactKey());
            grouped.computeIfAbsent(characterId, ignored -> new HashMap<>())
                    .computeIfAbsent(slot, ignored -> new ArrayList<>())
                    .add(source.getSourceFact());
        }
        return grouped;
    }

    private WorkerAnalysisEpisodePayload toEpisodeResponse(Episode episode) {
        if (episode == null) {
            return null;
        }
        return new WorkerAnalysisEpisodePayload(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getContentS3Key(),
                episode.getContentS3Version(),
                episode.getContentHash(),
                episode.getCharCount()
        );
    }
}
