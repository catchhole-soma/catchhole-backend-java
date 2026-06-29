package org.monitoring.catchholebackend.domain.character.mapper;

import java.util.Locale;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.entity.WorkCharacter;
import org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;
import org.monitoring.catchholebackend.global.exception.AppException;
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

    public String toFactKey(SettingCandidate candidate) {
        return candidate.getAttributeName().trim();
    }

    public CharacterFactType toFactType(String factKey) {
        String normalized = factKey.toLowerCase(Locale.ROOT);
        if (normalized.equals("age")) {
            return CharacterFactType.AGE;
        }
        if (normalized.equals("level")) {
            return CharacterFactType.LEVEL;
        }
        if (normalized.equals("stat") || normalized.equals("stats")
                || normalized.startsWith("stat.") || normalized.startsWith("stats.")) {
            return CharacterFactType.STAT;
        }
        if (normalized.equals("skill") || normalized.equals("skills")
                || normalized.startsWith("skill.") || normalized.startsWith("skills.")) {
            return CharacterFactType.SKILL;
        }
        if (normalized.equals("item") || normalized.equals("items")
                || normalized.startsWith("item.") || normalized.startsWith("items.")) {
            return CharacterFactType.ITEM;
        }
        if (normalized.equals("status") || normalized.equals("statuses") || normalized.equals("current_status")
                || normalized.startsWith("status.") || normalized.startsWith("statuses.")) {
            return CharacterFactType.STATUS;
        }
        if (normalized.equals("time") || normalized.equals("timeline")
                || normalized.startsWith("time.") || normalized.startsWith("timeline.")) {
            return CharacterFactType.TIME;
        }
        throw new AppException(CharacterErrorCode.SETTING_CANDIDATE_FACT_TYPE_UNSUPPORTED);
    }

    private String normalizeFactValue(String value) {
        return value == null ? null : value.trim();
    }
}
