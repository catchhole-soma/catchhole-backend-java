package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

/** #381 확정된 인물 발견 후보가 남긴 별칭 원문. 후보 엔티티 없이 인물 id와 이름만 읽는다. */
public record CharacterDiscoveryAlias(UUID characterId, String entityName) {
}
