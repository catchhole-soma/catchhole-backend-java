package org.monitoring.catchholebackend.domain.analysis.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisFailureNotificationMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisFailureNotificationRepository;
import org.monitoring.catchholebackend.global.notification.DiscordWebhookClient;

@DisplayName("분석 실패 알림 발송과 전송 실패 격리")
class AnalysisFailureNotificationDispatcherTest {
    final AnalysisFailureNotificationRepository repository = mock(AnalysisFailureNotificationRepository.class);
    final DiscordWebhookClient client = mock(DiscordWebhookClient.class);
    final AnalysisFailureNotificationMapper mapper = new AnalysisFailureNotificationMapper();
    final AnalysisFailureNotification event = new AnalysisFailureNotification(UUID.randomUUID(), UUID.randomUUID(), 2L,
            UUID.randomUUID(), UUID.randomUUID(), 3, "WORLD_COMPARISON", "LLM_PROVIDER_ERROR", 2,
            LocalDateTime.of(2026, 10, 23, 15, 0), 1, UUID.randomUUID());

    @Test
    @DisplayName("전송 확인을 받은 뒤에만 발송 완료로 기록한다")
    void acknowledgesOnlySuccessfulDelivery() {
        when(repository.claimNext()).thenReturn(Optional.of(event));
        when(client.send(anyString())).thenReturn(new DiscordWebhookClient.Delivery(true, Duration.ZERO));
        try (var dispatcher = new AnalysisFailureNotificationDispatcher(repository, client, mapper, "prod")) {
            dispatcher.dispatchNext();
        }
        var order = inOrder(client, repository);
        order.verify(repository).claimNext();
        order.verify(client).send(contains("세계관 비교 실패"));
        order.verify(repository).markDelivered(event);
        verify(repository, never()).postpone(any(), any());
    }

    @Test
    @DisplayName("429 이후에는 대기 기록을 보존하고 다른 알림의 즉시 전송도 멈춘다")
    void retainsFailureAndPausesWholeDestination() {
        when(repository.claimNext()).thenReturn(Optional.of(event));
        when(client.send(anyString())).thenReturn(new DiscordWebhookClient.Delivery(false, Duration.ofSeconds(17)));
        try (var dispatcher = new AnalysisFailureNotificationDispatcher(repository, client, mapper, "prod")) {
            dispatcher.dispatchNext();
            dispatcher.dispatchNext();
        }
        verify(repository).postpone(event, Duration.ofSeconds(17));
        verify(repository, times(1)).claimNext();
        verify(repository, never()).markDelivered(any());
    }

    @Test
    @DisplayName("발송 완료 기록이 실패해도 분석 경로에 예외를 전파하지 않는다")
    void isolatesAcknowledgementFailure() {
        when(repository.claimNext()).thenReturn(Optional.of(event));
        when(client.send(anyString())).thenReturn(new DiscordWebhookClient.Delivery(true, Duration.ZERO));
        doThrow(new IllegalStateException("secret raw error")).when(repository).markDelivered(event);
        try (var dispatcher = new AnalysisFailureNotificationDispatcher(repository, client, mapper, "prod")) {
            dispatcher.dispatchNext();
        }
        verify(repository, never()).postpone(any(), any());
    }

    @Test
    @DisplayName("알림에는 고정 오류 문구와 식별자를 넣고 임의 환경값과 알 수 없는 오류 코드는 내보내지 않는다")
    void formatsSafeDiagnosticContext() {
        String message = mapper.toMessage(event, "@everyone private-config");
        assertThat(message).contains("[기타]", "3화", "실패 항목: 2개", "회원 ID: 2", event.id().toString())
                .doesNotContain("@everyone", "private-config");
        var unknown = new AnalysisFailureNotification(event.id(), event.workId(), 2L, event.jobId(), null, null,
                "ANALYSIS_JOB", "private raw response", 1, event.occurredAt(), 1, event.leaseToken());
        assertThat(mapper.toMessage(unknown, "prod")).contains("UNEXPECTED_ERROR").doesNotContain("private raw response");
    }
}
