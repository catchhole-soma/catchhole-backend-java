package org.monitoring.catchholebackend.domain.character.processor;

import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;

public record SettingCandidateSchemaMatch(
        CharacterSettingSchema matchedSchema,
        String factKey
) {
}
