package org.monitoring.catchholebackend.domain.character.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactDetailResponse;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactSearchResponse;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.processor.CharacterSettingDisplayNameResolver;
import org.monitoring.catchholebackend.domain.character.processor.CharacterFactSourceResolver;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CharacterFactMapper {

    private final CharacterSettingDisplayNameResolver characterSettingDisplayNameResolver;
    private final CharacterFactSourceResolver characterFactSourceResolver;

    public CharacterFactSearchResponse toSearchResponse(
            CharacterFact fact,
            List<CharacterSettingSchema> schemas,
            boolean contributesToCurrentSnapshot
    ) {
        Episode sourceEpisode = characterFactSourceResolver.resolveEpisode(fact);

        return new CharacterFactSearchResponse(
                fact.getId(),
                fact.getFactType(),
                fact.getFactType().getToKorean(),
                resolveDisplayName(fact, schemas),
                fact.getFactValue(),
                contributesToCurrentSnapshot,
                contributesToCurrentSnapshot,
                fact.getWorkCharacter().getId(),
                fact.getWorkCharacter().getName(),
                sourceEpisode == null ? null : sourceEpisode.getId(),
                sourceEpisode == null ? null : sourceEpisode.getEpisodeNo(),
                fact.getEffectiveFromEpisodeNo()
        );
    }

    public CharacterFactDetailResponse toDetailResponse(
            CharacterFact fact,
            List<CharacterSettingSchema> schemas,
            boolean contributesToCurrentSnapshot
    ) {
        SettingCandidate candidate = fact.getSettingCandidate();
        Episode sourceEpisode = characterFactSourceResolver.resolveEpisode(fact);

        return new CharacterFactDetailResponse(
                fact.getId(),
                fact.getFactType(),
                fact.getFactType().getToKorean(),
                fact.getFactKey(),
                resolveDisplayName(fact, schemas),
                fact.getFactValue(),
                contributesToCurrentSnapshot,
                contributesToCurrentSnapshot,
                fact.getEffectiveFromEpisodeNo(),
                fact.getWorkCharacter().getId(),
                fact.getWorkCharacter().getName(),
                candidate == null ? null : candidate.getId(),
                sourceEpisode == null ? null : sourceEpisode.getId(),
                sourceEpisode == null ? null : sourceEpisode.getEpisodeNo(),
                extractEvidenceQuotes(candidate)
        );
    }

    private String resolveDisplayName(
            CharacterFact fact,
            List<CharacterSettingSchema> schemas
    ) {
        return characterSettingDisplayNameResolver.resolve(
                fact.getFactType(),
                fact.getFactKey(),
                fact.getValueJson(),
                schemas
        );
    }

    private List<String> extractEvidenceQuotes(SettingCandidate candidate) {
        if (candidate == null || candidate.getEvidenceSpans() == null || !candidate.getEvidenceSpans().isArray()) {
            return List.of();
        }

        List<String> quotes = new ArrayList<>();
        for (JsonNode evidenceSpan : candidate.getEvidenceSpans()) {
            JsonNode quote = evidenceSpan.get("quote");
            if (quote != null && quote.isTextual()) {
                quotes.add(quote.textValue());
            }
        }
        return List.copyOf(quotes);
    }
}
