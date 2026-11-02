package org.monitoring.catchholebackend.domain.analysis.repository;

import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotificationDelivery;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class AnalysisFailureNotificationRepository {
    private static final JsonMapper SNAPSHOT_JSON = new JsonMapper();
    private final JdbcTemplate jdbc;

    /** 묶음을 잠근 다음 별도 statement snapshot에서 구성원을 읽는다. HTTP 전에 커밋한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<AnalysisFailureNotificationDelivery> claimDelivery() {
        var groups = jdbc.query("""
                SELECT d.id, d.execution_attempt_id FROM analysis_failure_notification_deliveries d
                WHERE d.ready_at <= localtimestamp
                  AND EXISTS (SELECT 1 FROM analysis_failure_notifications n
                    WHERE n.delivery_id=d.id AND n.delivered_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM analysis_failure_notifications n
                    WHERE n.delivery_id=d.id AND n.delivered_at IS NULL AND n.available_at > localtimestamp)
                ORDER BY d.ready_at, d.created_at, d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """, (rs, row) -> new DeliveryKey(rs.getObject("id", UUID.class), rs.getObject("execution_attempt_id", UUID.class)));
        if (groups.isEmpty()) return Optional.empty();
        var group = groups.getFirst();
        jdbc.update("UPDATE analysis_failure_notification_deliveries SET sealed=true WHERE id=?", group.id());
        UUID leaseToken = UUID.randomUUID();
        var events = jdbc.query("""
                WITH claimed AS (
                    UPDATE analysis_failure_notifications AS target SET lease_token=?, attempts=attempts+1,
                        available_at=localtimestamp + interval '2 minutes'
                    WHERE delivery_id=? AND delivered_at IS NULL AND available_at <= localtimestamp
                      AND NOT EXISTS (SELECT 1 FROM analysis_failure_notifications sibling
                        WHERE sibling.delivery_id=target.delivery_id AND sibling.delivered_at IS NULL
                          AND sibling.available_at > localtimestamp)
                    RETURNING *
                )
                SELECT n.*, w.member_id FROM claimed n JOIN works w ON w.id=n.work_id
                ORDER BY n.occurred_at, n.id
                """, (rs, row) -> notification(rs), leaseToken, group.id());
        if (events.isEmpty()) return Optional.empty();
        return Optional.of(new AnalysisFailureNotificationDelivery(group.id(), group.attemptId(), events));
    }

    private record DeliveryKey(UUID id, UUID attemptId) {}

    public void markDelivered(AnalysisFailureNotificationDelivery delivery) {
        jdbc.update("""
                UPDATE analysis_failure_notifications SET delivered_at=localtimestamp, lease_token=NULL
                WHERE delivery_id=? AND lease_token=? AND delivered_at IS NULL
                """, delivery.id(), delivery.leaseToken());
    }

    public void postpone(AnalysisFailureNotificationDelivery delivery, Duration delay) {
        jdbc.update("""
                UPDATE analysis_failure_notifications
                SET available_at=localtimestamp + (? * interval '1 millisecond'), lease_token=NULL
                WHERE delivery_id=? AND lease_token=? AND delivered_at IS NULL
                """, delay.toMillis(), delivery.id(), delivery.leaseToken());
    }

    public void deleteDeliveredHistory() {
        jdbc.update("DELETE FROM analysis_failure_notifications WHERE delivered_at < localtimestamp - interval '30 days'");
        jdbc.update("DELETE FROM analysis_failure_notification_deliveries d WHERE d.sealed AND NOT EXISTS (SELECT 1 FROM analysis_failure_notifications n WHERE n.delivery_id=d.id)");
    }

    private static AnalysisFailureNotification notification(ResultSet rs) throws SQLException {
        return new AnalysisFailureNotification(
                rs.getObject("id", UUID.class), rs.getObject("work_id", UUID.class),
                rs.getObject("member_id", Long.class), rs.getObject("analysis_job_id", UUID.class),
                rs.getObject("episode_id", UUID.class), rs.getObject("episode_no", Integer.class),
                rs.getString("failure_kind"), rs.getString("failure_code"), rs.getInt("failure_count"),
                rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getInt("attempts"),
                rs.getObject("lease_token", UUID.class), diagnostics(rs.getString("failure_diagnostics")),
                rs.getObject("execution_job_id", UUID.class), rs.getObject("source_analysis_job_id", UUID.class),
                rs.getObject("comparison_batch_id", UUID.class), rs.getString("job_type"),
                targetEpisodeNos(rs), rs.getString("candidate_review_status"), failedCandidateIds(rs), rs.getObject("continues_after_candidate_failure", Boolean.class));
    }

    private static AnalysisFailureDiagnostics diagnostics(String snapshot) {
        if (snapshot == null) return null;
        try { return SNAPSHOT_JSON.readValue(snapshot, AnalysisFailureDiagnostics.class); }
        catch (RuntimeException ex) { return null; }
    }

    private static List<UUID> failedCandidateIds(ResultSet rs) throws SQLException {
        var array = rs.getArray("failed_candidate_ids");
        if (array == null) return List.of();
        try { return Arrays.asList((UUID[]) array.getArray()); }
        finally { array.free(); }
    }

    private static List<Integer> targetEpisodeNos(ResultSet rs) throws SQLException {
        var array = rs.getArray("target_episode_nos");
        if (array == null) return null;
        try { return Arrays.asList((Integer[]) array.getArray()); }
        finally { array.free(); }
    }
}
