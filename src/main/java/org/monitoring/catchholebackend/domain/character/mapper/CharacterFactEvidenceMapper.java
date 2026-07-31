package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceEpisodeResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceSpanResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
public class CharacterFactEvidenceMapper {

    public CharacterFactEvidenceResponse toResponse(CharacterFact fact, String content) {
        SettingCandidate candidate = fact.getSettingCandidate();
        Episode episode = resolveEpisode(fact, candidate);

        return new CharacterFactEvidenceResponse(
                fact.getId(),
                candidate == null ? null : candidate.getId(),
                toEpisodeResponse(episode),
                content,
                toEvidenceSpans(candidate == null ? null : candidate.getEvidenceSpans())
        );
    }

    private CharacterFactEvidenceEpisodeResponse toEpisodeResponse(Episode episode) {
        if (episode == null) {
            return null;
        }
        return new CharacterFactEvidenceEpisodeResponse(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle()
        );
    }

    private Episode resolveEpisode(CharacterFact fact, SettingCandidate candidate) {
        if (candidate != null && candidate.getEpisode() != null) {
            return candidate.getEpisode();
        }
        return fact.getSourceEpisode();
    }

    private List<CharacterFactEvidenceSpanResponse> toEvidenceSpans(JsonNode evidenceSpans) {
        if (evidenceSpans == null || !evidenceSpans.isArray()) {
            return List.of();
        }

        List<CharacterFactEvidenceSpanResponse> responses = new ArrayList<>();
        for (JsonNode evidenceSpan : evidenceSpans) {
            String quote = textOrNull(evidenceSpan.get("quote"));
            if (quote == null || quote.isBlank()) {
                continue;
            }
            responses.add(new CharacterFactEvidenceSpanResponse(
                    quote,
                    integerOrNull(evidenceSpan, "start_offset", "startOffset"),
                    integerOrNull(evidenceSpan, "end_offset", "endOffset")
            ));
        }
        return List.copyOf(responses);
    }

    private Integer integerOrNull(JsonNode node, String snakeCaseName, String camelCaseName) {
        JsonNode value = node.get(snakeCaseName);
        if (value == null) {
            value = node.get(camelCaseName);
        }
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                ? value.intValue()
                : null;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
