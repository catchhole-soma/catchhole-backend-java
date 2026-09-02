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
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSnapshotSource;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotAccessor;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSnapshotSlot;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisJobWorkerMapper {

    private final CharacterSnapshotAccessor snapshotAccessor;

    public WorkerAnalysisJobPayload toResponse(
            AnalysisJob analysisJob,
            Episode episode,
            List<CharacterSettingSchema> characterSettingSchemas,
            List<WorkCharacter> knownCharacters,
            List<CharacterSnapshotSource> activeStatusSources
    ) {
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
                knownCharacters.stream()
                        .map(character -> toKnownCharacterResponse(
                                character,
                                statusSourceFactsByCharacter.getOrDefault(character.getId(), Map.of())
                        ))
                        .toList(),
                toEpisodeResponse(episode)
        );
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
