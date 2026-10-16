package org.monitoring.catchholebackend.domain.chat.service;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "chat.session-cleanup.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatSessionCleanup {
    private final ChatSessionService sessions;
    private final MeterRegistry metrics;

    @Scheduled(fixedDelayString = "${chat.session-cleanup-delay-ms:60000}")
    public void expireSessions() {
        for (var id : sessions.expiredSessionIds()) {
            try {
                sessions.expireSession(id);
            } catch (RuntimeException ignored) {
                metrics.counter("chat.session.cleanup", "outcome", "failed").increment();
            }
        }
    }
}
