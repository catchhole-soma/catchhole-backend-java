package org.monitoring.catchholebackend.domain.character.processor;

import java.util.UUID;

public record CharacterTimelineCursor(
        UUID characterId,
        String filterFingerprint,
        Integer fromEpisodeNo,
        int offset
) {
}
