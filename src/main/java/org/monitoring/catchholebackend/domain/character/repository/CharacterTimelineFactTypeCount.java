package org.monitoring.catchholebackend.domain.character.repository;

import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

public record CharacterTimelineFactTypeCount(
        CharacterFactType factType,
        long count
) {
}
