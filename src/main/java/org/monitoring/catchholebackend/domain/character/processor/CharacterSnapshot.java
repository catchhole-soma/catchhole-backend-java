package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;

public record CharacterSnapshot(
        Integer currentAge,
        Integer currentLevel,
        JsonNode profileJson,
        JsonNode statsJson,
        JsonNode skillsJson,
        JsonNode itemsJson,
        JsonNode statusesJson
) {
}
