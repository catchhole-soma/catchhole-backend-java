package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;

public record CharacterSnapshot(
        JsonNode statsJson,
        JsonNode skillsJson,
        JsonNode itemsJson,
        JsonNode statusesJson
) {
}
