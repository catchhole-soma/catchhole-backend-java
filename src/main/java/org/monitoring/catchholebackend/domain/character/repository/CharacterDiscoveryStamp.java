package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

/** #387 자동 누적 live 읽기 지문용 확정 인물 발견. 후보 엔티티 없이 연결 인물 id와 후보 id만 읽는다. */
public record CharacterDiscoveryStamp(UUID characterId, UUID candidateId) {
}
