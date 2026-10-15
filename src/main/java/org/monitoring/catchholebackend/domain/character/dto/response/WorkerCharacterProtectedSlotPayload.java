package org.monitoring.catchholebackend.domain.character.dto.response;

import org.monitoring.catchholebackend.domain.character.type.CharacterFactType;

public record WorkerCharacterProtectedSlotPayload(CharacterFactType factType, String factKey) {}
