package org.monitoring.catchholebackend.domain.character.repository;

import java.util.UUID;

/** #381 검수 live 설정 색인·fingerprint용 인물 scalar 행. JSON snapshot 열은 읽지 않는다. */
public record CharacterSettingVersion(UUID id, String name, long snapshotVersion) {
}
