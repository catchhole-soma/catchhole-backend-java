package org.monitoring.catchholebackend.domain.analysis.event;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Hibernate의 Spring BeanContainer가 주입한다. callback은 조회 없이 변경된 값만 복사한다. */
@Component
@RequiredArgsConstructor
public class AnalysisMetricsEntityListener {
    private final ApplicationEventPublisher events;
    private final Map<Object, State> previous = Collections.synchronizedMap(new WeakHashMap<>());
    private record State(int attempt, int claims, AnalysisJobStatus status, LocalDateTime pending,
                         LocalDateTime ready, LocalDateTime candidateTerminal) {}
    @PostLoad public void loaded(Object entity) { previous.put(entity, state(entity)); }
    @PrePersist @PreUpdate public void captureTerminal(Object entity) {
        if (entity instanceof SettingCandidate c) c.captureComparisonMetrics();
        if (entity instanceof WorldSettingCandidate c) c.captureComparisonMetrics();
    }
    @PostPersist public void inserted(Object entity) {
        if (entity instanceof AnalysisJob job && job.getAttemptRequestedAt() != null) {
            emit(job, "accepted", null, job.getAttemptRequestedAt(), null);
            if (job.isMetricsUserRetry()) emit(job, "retry", null, null, null);
        }
        changed(entity);
    }
    @PostUpdate public void updated(Object entity) { changed(entity); }
    @PreRemove public void removed(Object entity) {
        // 삭제 transaction이 취소→삭제를 flush 한 번으로 묶어도 immutable canceled 결과를 보존한다.
        if (entity instanceof AnalysisJob job && job.getResultReadyAt() != null) {
            State old = previous.get(entity);
            if (old == null || old.ready == null) emit(job, "result", job.getAttemptRequestedAt(), job.getResultReadyAt(), job.getResultOutcome());
        }
        previous.remove(entity);
    }
    private void changed(Object entity) {
        State now = state(entity);
        State old = previous.put(entity, now);
        if (entity instanceof AnalysisJob job) {
            if (old != null && old.attempt != now.attempt && job.getAttemptRequestedAt() != null) {
                emit(job, "accepted", null, job.getAttemptRequestedAt(), null);
                emit(job, "retry", null, null, null);
            }
            if (old != null && now.claims > old.claims) emit(job, "claim", old.pending == null ? job.getCreatedAt() : old.pending, job.getStartedAt(), null);
            if (old != null && old.status == AnalysisJobStatus.RUNNING) {
                if (now.status == AnalysisJobStatus.PENDING) emit(job, "recovery", null, null, "requeued");
                if (now.status == AnalysisJobStatus.FAILED && job.getFailureCode() == AnalysisFailureCode.WORKER_LEASE_EXPIRED) emit(job, "recovery", null, null, "failed");
            }
            if (job.getResultReadyAt() != null && (old == null || old.ready == null || old.attempt != now.attempt))
                emit(job, "result", job.getAttemptRequestedAt(), job.getResultReadyAt(), job.getResultOutcome());
            else if (now.status == AnalysisJobStatus.SUCCEEDED && (old == null || old.status != now.status))
                emit(job, "reconcile", null, null, null);
        } else if (now.candidateTerminal != null && (old == null || !now.candidateTerminal.equals(old.candidateTerminal))) {
            if (entity instanceof SettingCandidate c && c.getAnalysisJob() != null)
                events.publishEvent(new AnalysisMetricsEvent("reconcile", c.getAnalysisJob().getId(), c.getMetricsSourceAttemptNo(), null, null, null, null));
            if (entity instanceof WorldSettingCandidate c)
                events.publishEvent(new AnalysisMetricsEvent("reconcile", c.getAnalysisJob().getId(), c.getMetricsSourceAttemptNo(), null, null, null, null));
        }
    }
    private void emit(AnalysisJob job, String kind, LocalDateTime from, LocalDateTime at, String detail) {
        events.publishEvent(new AnalysisMetricsEvent(kind, job.getId(), job.getMetricsAttemptNo(), AnalysisMetricsEvent.Labels.of(job), from, at, detail));
    }
    private State state(Object entity) {
        if (entity instanceof AnalysisJob j) return new State(j.getMetricsAttemptNo(), j.getClaimAttemptCount(), j.getStatus(), j.getPendingSince(), j.getResultReadyAt(), null);
        LocalDateTime terminal = entity instanceof SettingCandidate c ? c.getComparisonTerminalAt() : ((WorldSettingCandidate) entity).getComparisonTerminalAt();
        return new State(0, 0, null, null, null, terminal);
    }
}
