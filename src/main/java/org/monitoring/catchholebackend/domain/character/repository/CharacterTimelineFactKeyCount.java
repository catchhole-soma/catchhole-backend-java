package org.monitoring.catchholebackend.domain.character.repository;

import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

public record CharacterTimelineFactKeyCount(
        CharacterFactType factType,
        String factKey,
        long count
) {
}
