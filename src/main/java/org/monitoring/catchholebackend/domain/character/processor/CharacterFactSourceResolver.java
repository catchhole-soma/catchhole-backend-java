package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;
import org.monitoring.catchholebackend.domain.character.entity.CharacterFact;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.stereotype.Component;

/**
 * CharacterFact의 직접 출처를 우선하고 레거시 Fact만 SettingCandidate 회차로 보완한다.
 * 상세, 타임라인, 원문 근거가 같은 회차 판정 규칙을 사용하도록 한 곳에 모은다.
 */
@Component
public class CharacterFactSourceResolver {

    public Episode resolveEpisode(CharacterFact fact) {
        if (fact.getSourceEpisode() != null) {
            return fact.getSourceEpisode();
        }
        SettingCandidate candidate = fact.getSettingCandidate();
        return candidate == null ? null : candidate.getEpisode();
    }

    public boolean hasEvidence(CharacterFact fact) {
        SettingCandidate candidate = fact.getSettingCandidate();
        if (candidate == null) {
            return false;
        }
        JsonNode spans = candidate.getEvidenceSpans();
        if (spans == null || !spans.isArray()) {
            return false;
        }
        for (JsonNode span : spans) {
            JsonNode quote = span.get("quote");
            if (quote != null && quote.isTextual() && !quote.textValue().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
