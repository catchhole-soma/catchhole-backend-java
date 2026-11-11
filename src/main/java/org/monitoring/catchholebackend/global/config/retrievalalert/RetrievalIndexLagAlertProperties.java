package org.monitoring.catchholebackend.global.config.retrievalalert;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검색 색인 지연 알림(#422). 켜는 조건(scheduling-enabled)과 확인 주기(fixed-delay-ms)는 Config와 @Scheduled가 직접 읽는다.
 *
 * @param threshold      원고가 올라온 뒤 이 시간이 지나도 색인이 없으면 지연으로 센다.
 * @param repeatInterval 지연 회차가 늘었을 때 직전 알림 뒤 이 시간이 지나야 다시 보낸다.
 */
@ConfigurationProperties("retrieval.index-lag-alert")
public record RetrievalIndexLagAlertProperties(Duration threshold, Duration repeatInterval) {
    public RetrievalIndexLagAlertProperties {
        if (threshold == null) threshold = Duration.ofMinutes(60);
        if (repeatInterval == null) repeatInterval = Duration.ofHours(1);
        if (threshold.isNegative() || threshold.isZero() || repeatInterval.isNegative() || repeatInterval.isZero())
            throw new IllegalArgumentException("검색 색인 지연 알림 설정이 올바르지 않습니다.");
    }
}
