package org.monitoring.catchholebackend.domain.character.mapper;

import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.springframework.stereotype.Component;

@Component
public class SettingCandidatePromotionMapper {

    public WorkCharacter toWorkCharacter(SettingCandidate candidate) {
        return WorkCharacter.create(
                candidate.getWork(),
                toCharacterName(candidate),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                candidate.getEpisode() == null ? null : candidate.getEpisode().getId()
        );
    }

    public CharacterFact toCharacterFact(
            SettingCandidate candidate,
            WorkCharacter character,
            CharacterFactType factType,
            String factKey
    ) {
        return CharacterFact.create(
                character,
                factType,
                factKey,
                normalizeFactValue(candidate.getAttributeValue()),
                normalizeFactValue(candidate.getAttributeValue()),
                candidate.getValueJson(),
                candidate.getEpisode(),
                candidate.getSourceChunkId(),
                candidate.getAnalysisJob(),
                candidate.getConfidence(),
                candidate.getEpisode() == null ? null : candidate.getEpisode().getEpisodeNo()
        );
    }

    public String toCharacterName(SettingCandidate candidate) {
        return candidate.getEntityName().trim();
    }

    private String normalizeFactValue(String value) {
        return value == null ? null : value.trim();
    }
}
