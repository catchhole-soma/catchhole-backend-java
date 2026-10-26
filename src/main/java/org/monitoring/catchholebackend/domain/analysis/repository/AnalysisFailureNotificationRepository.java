package org.monitoring.catchholebackend.domain.analysis.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalysisFailureNotificationRepository {
    private final JdbcTemplate jdbc;

    /** 단일 statement로 선점한다. HTTP 호출 중에는 transaction/DB 연결을 보유하지 않는다. */
    public Optional<AnalysisFailureNotification> claimNext() {
        UUID leaseToken = UUID.randomUUID();
        return jdbc.query("""
                WITH next AS (
                    SELECT id FROM analysis_failure_notifications
                    WHERE delivered_at IS NULL AND available_at <= localtimestamp
                    ORDER BY available_at, occurred_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                ), claimed AS (
                    UPDATE analysis_failure_notifications n
                    SET lease_token = ?, attempts = attempts + 1,
                        available_at = localtimestamp + interval '2 minutes'
                    FROM next WHERE n.id = next.id RETURNING n.*
                )
                SELECT n.*, w.member_id FROM claimed n JOIN works w ON w.id = n.work_id
                """, (rs, row) -> new AnalysisFailureNotification(
                rs.getObject("id", UUID.class), rs.getObject("work_id", UUID.class),
                rs.getObject("member_id", Long.class), rs.getObject("analysis_job_id", UUID.class),
                rs.getObject("episode_id", UUID.class), rs.getObject("episode_no", Integer.class),
                rs.getString("failure_kind"), rs.getString("failure_code"), rs.getInt("failure_count"),
                rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getInt("attempts"),
                rs.getObject("lease_token", UUID.class)), leaseToken).stream().findFirst();
    }

    public void markDelivered(AnalysisFailureNotification notification) {
        jdbc.update("""
                UPDATE analysis_failure_notifications SET delivered_at = localtimestamp, lease_token = NULL
                WHERE id = ? AND lease_token = ? AND delivered_at IS NULL
                """, notification.id(), notification.leaseToken());
    }

    public void postpone(AnalysisFailureNotification notification, Duration delay) {
        jdbc.update("""
                UPDATE analysis_failure_notifications
                SET available_at = localtimestamp + (? * interval '1 millisecond'), lease_token = NULL
                WHERE id = ? AND lease_token = ? AND delivered_at IS NULL
                """, delay.toMillis(), notification.id(), notification.leaseToken());
    }

    public void deleteDeliveredHistory() {
        jdbc.update("DELETE FROM analysis_failure_notifications WHERE delivered_at < localtimestamp - interval '30 days'");
    }
}
