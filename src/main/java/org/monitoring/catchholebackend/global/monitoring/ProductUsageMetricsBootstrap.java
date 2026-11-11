package org.monitoring.catchholebackend.global.monitoring;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.productusage.service.ProductUsageService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 웹 서버가 요청을 받기 전에 한 번 활성화한다. migration 시각으로 소급하지 않는다. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "product-usage.metrics.enabled", havingValue = "true")
public class ProductUsageMetricsBootstrap implements SmartInitializingSingleton {
    private final ProductUsageService usage;
    @Override public void afterSingletonsInstantiated() { usage.initialize(LocalDateTime.now()); }
}
