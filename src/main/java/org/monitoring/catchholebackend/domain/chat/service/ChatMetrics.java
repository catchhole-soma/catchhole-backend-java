package org.monitoring.catchholebackend.domain.chat.service;

import io.micrometer.core.instrument.MeterRegistry;

import org.monitoring.catchholebackend.domain.chat.type.ChatState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "chat.metrics-enabled", havingValue = "true", matchIfMissing = true)
public class ChatMetrics {
    private final ChatServiceImpl service;
    private final MeterRegistry metrics;
    private final Map<String, AtomicLong> values = new HashMap<>();
    private final AtomicLong lastSuccess = new AtomicLong();

    public ChatMetrics(ChatServiceImpl service, MeterRegistry metrics) {
        this.service = service;
        this.metrics = metrics;
        for (var state : ChatState.values())
            register("chat.execution.count", "state", state.name());
        register("chat.usage.count", "status", "usage_pending");
        register("chat.usage.count", "status", "usage_unknown");
        metrics.gauge("chat.snapshot.last.success.seconds", lastSuccess);
    }

    private void register(String name, String label, String value) {
        var number = new AtomicLong();
        values.put(value, number);
        metrics.gauge(name, List.of(io.micrometer.core.instrument.Tag.of(label, value)), number);
    }

    @Scheduled(fixedDelayString = "${chat.metrics-delay-ms:15000}")
    public void refresh() {
        try {
            var snapshot = service.metricSnapshot();
            values.forEach((key, value) -> value.set(snapshot.getOrDefault(key, 0L)));
            lastSuccess.set(java.time.Instant.now().getEpochSecond());
        } catch (Exception ignored) {
            metrics.counter("chat.snapshot.failure").increment();
        }
    }
}
