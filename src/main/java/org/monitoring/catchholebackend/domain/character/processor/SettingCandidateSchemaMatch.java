package org.monitoring.catchholebackend.domain.character.processor;

import org.monitoring.catchholebackend.domain.character.entity.CharacterSettingSchema;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactCanonicalKeyResolution;

public record SettingCandidateSchemaMatch(
        CharacterSettingSchema matchedSchema,
        String factKey,
        CharacterFactCanonicalKeyResolution canonicalKeyResolution
) {
}
