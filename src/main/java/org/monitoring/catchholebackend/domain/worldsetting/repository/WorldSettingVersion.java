package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.UUID;

/** #381 검수 live 설정 색인·fingerprint용 세계관 scalar 행. properties JSON은 읽지 않는다. */
public record WorldSettingVersion(UUID id, String subjectName, long version) {
}
