package org.monitoring.catchholebackend.domain.worldsetting.repository;

import java.util.UUID;

/** #381 세계관 대상별 확정 현재값 후보(이력 전용 제외)의 가장 늦은 출처 회차. */
public record WorldSettingLatestSourceEpisode(UUID worldSettingId, Integer episodeNo) {
}
