package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.UUID;

/** #387 자동 누적 live 읽기 지문용 세계관 대상별 확정 현재값 후보(이력 전용 제외) 수. */
public record WorldSettingCurrentSourceCount(UUID worldSettingId, long candidateCount) {
}
