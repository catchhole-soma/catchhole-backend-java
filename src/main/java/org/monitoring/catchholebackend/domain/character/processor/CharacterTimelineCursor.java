package org.monitoring.catchholebackend.domain.character.processor;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.character.type.CharacterTimelineFactFilter;

public record CharacterTimelineCursor(
        UUID characterId,
        CharacterTimelineFactFilter factType,
        Integer fromEpisodeNo,
        int offset
) {
}
