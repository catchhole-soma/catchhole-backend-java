package org.monitoring.catchholebackend.domain.analysis.dto;

import java.util.List;
import java.util.UUID;

/** 최초 발송 선점 때 봉인된 outbox 묶음. 재전송도 같은 구성과 ID를 사용한다. */
public record AnalysisFailureNotificationDelivery(UUID id, UUID executionAttemptId,
        List<AnalysisFailureNotification> notifications) {
    public AnalysisFailureNotificationDelivery {
        notifications = List.copyOf(notifications);
        if (notifications.isEmpty() || notifications.size() > 4) throw new IllegalArgumentException("Invalid delivery size");
    }
    public UUID leaseToken() { return notifications.getFirst().leaseToken(); }
    public int attempts() { return notifications.getFirst().attempts(); }
}
