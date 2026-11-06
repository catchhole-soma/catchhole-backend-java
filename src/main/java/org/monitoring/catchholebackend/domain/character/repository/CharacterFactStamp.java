package org.monitoring.catchholebackend.domain.character.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** #387 자동 누적 live 읽기 지문용 인물별 Fact 수와 가장 늦은 생성 시각. Fact는 추가 전용이라 이력 추가마다 달라진다. */
public record CharacterFactStamp(UUID characterId, long factCount, LocalDateTime latestCreatedAt) {
}
