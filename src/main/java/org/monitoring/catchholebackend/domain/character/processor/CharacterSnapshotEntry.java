package org.monitoring.catchholebackend.domain.character.processor;

import com.fasterxml.jackson.databind.JsonNode;

/** 현재 snapshot 값 한 건이다. 원문 provenance는 별도 CharacterSnapshotSource가 소유한다. */
public record CharacterSnapshotEntry(
        CharacterSnapshotSlot slot,
        String factValue,
        JsonNode valueJson,
        boolean factValuePersisted
) {
}
