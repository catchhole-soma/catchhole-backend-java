package org.monitoring.catchholebackend.domain.analysis.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.StreamSupport;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisCharacterSettingSchemaPayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisEpisodePayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisJobPayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisKnownCharacterPayload;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobWorkerMapper {

    public WorkerAnalysisJobPayload toPayload(
            AnalysisJob analysisJob,
            List<Episode> episodes,
            List<CharacterSettingSchema> characterSettingSchemas,
            List<WorkCharacter> knownCharacters
    ) {
        return new WorkerAnalysisJobPayload(
                analysisJob.getId(),
                analysisJob.getJobType(),
                analysisJob.getWork().getId(),
                analysisJob.getWork().getTitle(),
                analysisJob.getBatch().getId(),
                analysisJob.getModelName(),
                analysisJob.getCurrentStep(),
                characterSettingSchemas.stream()
                        .map(this::toCharacterSettingSchemaPayload)
                        .toList(),
                knownCharacters.stream()
                        .map(this::toKnownCharacterPayload)
                        .toList(),
                episodes.stream()
                        .map(this::toEpisodePayload)
                        .toList()
        );
    }

    private WorkerAnalysisCharacterSettingSchemaPayload toCharacterSettingSchemaPayload(
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

    private WorkerAnalysisKnownCharacterPayload toKnownCharacterPayload(WorkCharacter character) {
        return new WorkerAnalysisKnownCharacterPayload(
                character.getId(),
                character.getName()
        );
    }

    private WorkerAnalysisEpisodePayload toEpisodePayload(Episode episode) {
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
