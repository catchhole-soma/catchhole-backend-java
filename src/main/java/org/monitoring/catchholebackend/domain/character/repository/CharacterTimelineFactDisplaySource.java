package org.monitoring.catchholebackend.domain.character.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

/**
 * 타임라인 facet의 factKey 표시명을 결정할 때 참조하는 Fact 값이다.
 */
public record CharacterTimelineFactDisplaySource(
        CharacterFactType factType,
        String factKey,
        JsonNode valueJson
) {
}
