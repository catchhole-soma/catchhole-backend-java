package org.monitoring.catchholebackend.domain.analysis.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 추가 관측 SQL은 business commit 뒤 별도 transaction에서 실행한다. */
@Service
@RequiredArgsConstructor
public class AnalysisResultReadyTracker {
    private final EntityManager em;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(UUID id, int attempt) {
        AnalysisJob job = em.find(AnalysisJob.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (job == null || job.getMetricsAttemptNo() != attempt || job.getAttemptRequestedAt() == null
                || job.getResultReadyAt() != null || job.getStatus() != AnalysisJobStatus.SUCCEEDED) return;
        // INCOMPLETE 자동 분석은 반영을 건너뛰므로 종료된 비교 결과만 관측한다.
        if (job.isAutomaticReview() && job.getAutomaticAppliedAt() == null
                && job.getJournalStatus() != AnalysisJournalStatus.INCOMPLETE) return;
        LocalDateTime ready = job.getCompletedAt();
        boolean failure = false;
        for (String entity : List.of("SettingCandidate", "WorldSettingCandidate")) {
            Object[] row = em.createQuery("select count(c), sum(case when c.comparisonTerminalAt is null and "
                    + "c.comparisonStatus in ('PENDING','PROCESSING') then 1 else 0 end), "
                    + "sum(case when c.comparisonTerminalOutcome = 'failure' or (c.comparisonTerminalAt is null and c.comparisonStatus = 'FAILED') then 1 else 0 end), "
                    + "max(c.comparisonTerminalAt) from " + entity + " c where c.analysisJob.id = :id", Object[].class)
                    .setParameter("id", id).getSingleResult();
            if (row[1] != null && ((Number)row[1]).longValue() > 0) return;
            failure |= row[2] != null && ((Number)row[2]).longValue() > 0;
            if (row[3] instanceof LocalDateTime terminal && terminal.isAfter(ready)) ready = terminal;
        }
        if (job.getAutomaticAppliedAt() != null && job.getAutomaticAppliedAt().isAfter(ready)) ready = job.getAutomaticAppliedAt();
        // INCOMPLETE journal은 원시 SUCCEEDED를 전체 성공으로 해석할 수 없다.
        String outcome = failure ? "partial_success" : job.getJournalStatus() == AnalysisJournalStatus.INCOMPLETE ? "failure" : "success";
        job.recordResultReady(attempt, ready, outcome);
    }
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Object[]> unresolved(int offset) {
        return em.createQuery("select job.id, job.metricsAttemptNo from AnalysisJob job where job.attemptRequestedAt is not null "
                + "and job.resultReadyAt is null and job.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED "
                + "order by job.id", Object[].class).setFirstResult(offset).setMaxResults(100).getResultList();
    }
}
