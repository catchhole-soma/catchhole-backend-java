package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.SettingCandidateReviewStatusResponse;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class SettingCandidateMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SettingCandidateResponse toResponse(SettingCandidate candidate) {
        Episode episode = candidate.getEpisode();
        AnalysisJob analysisJob = candidate.getAnalysisJob();

        return new SettingCandidateResponse(
                candidate.getId(),
                candidate.getWork().getId(),
                episode == null ? null : episode.getId(),
                candidate.getSourceChunkId(),
                analysisJob == null ? null : analysisJob.getId(),
                candidate.getEntityType(),
                candidate.getEntityName(),
                candidate.getAttributeName(),
                candidate.getAttributeValue(),
                candidate.getValueType(),
                toJsonValue(candidate.getValueJson()),
                toJsonValue(candidate.getEvidenceSpans()),
                candidate.getConfidence(),
                candidate.getReviewStatus(),
                toJsonValue(candidate.getRawAiResultJson()),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }

    public List<SettingCandidateResponse> toResponseList(List<SettingCandidate> candidates) {
        return candidates.stream()
                .map(this::toResponse)
                .toList();
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
