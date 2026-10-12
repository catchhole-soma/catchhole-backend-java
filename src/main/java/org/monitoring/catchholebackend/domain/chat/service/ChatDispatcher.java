package org.monitoring.catchholebackend.domain.chat.service;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;

import org.monitoring.catchholebackend.global.config.chat.ChatProperties;
import org.monitoring.catchholebackend.global.config.security.InternalApiProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
public class ChatDispatcher {
    private final ChatServiceImpl service;
    private final ChatProperties properties;
    private final InternalApiProperties internal;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
    private ExecutorService executor;

    @jakarta.annotation.PostConstruct
    public void initialize() {
        executor =
                new ThreadPoolExecutor(
                        properties.dispatchThreads(),
                        properties.dispatchThreads(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(properties.dispatchQueueCapacity()),
                        new ThreadPoolExecutor.AbortPolicy());
    }

    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    @TransactionalEventListener
    public void ready(ChatServiceImpl.Ready event) {
        if (properties.enabled()) enqueue(event.attemptId());
    }

    private void enqueue(UUID id) {
        if (!inFlight.add(id)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            send(id);
                        } finally {
                            inFlight.remove(id);
                        }
                    });
        } catch (RejectedExecutionException e) {
            inFlight.remove(id);
            metrics.counter("chat.dispatch", "outcome", "queue_full").increment();
        }
    }

    private void send(UUID id) {
        try {
            // Transaction has completed before either outbound HTTP or its timeout.
            var request = service.dispatch(id);
            var response =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    properties.pythonUrl()
                                                            + "/internal/v1/chat-executions"))
                                    .timeout(Duration.ofSeconds(3))
                                    .header("X-Internal-Api-Key", internal.apiKey())
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    json.writeValueAsString(request)))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            metrics.counter(
                            "chat.dispatch",
                            "outcome",
                            response.statusCode() == 202 ? "accepted" : "rejected")
                    .increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            metrics.counter("chat.dispatch", "outcome", "unconfirmed").increment();
        }
    }

    @Scheduled(fixedDelayString = "${chat.recovery-delay-ms:5000}")
    public void recover() {
        for (var id : service.recoveryIds())
            try {
                service.recover(id);
                metrics.counter("chat.recovery", "outcome", "checked").increment();
            } catch (Exception e) {
                metrics.counter("chat.recovery", "outcome", "failed").increment();
            }
        if (properties.enabled()) for (var id : service.queuedIds()) enqueue(id);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        http.close();
    }
}
