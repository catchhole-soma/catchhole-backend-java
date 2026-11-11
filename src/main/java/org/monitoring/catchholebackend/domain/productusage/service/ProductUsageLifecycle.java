package org.monitoring.catchholebackend.domain.productusage.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisResultReadyTracker;
import org.monitoring.catchholebackend.domain.productusage.event.ProductUsageBeforeWorkChange;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProductUsageLifecycle {
    private final EntityManager em;
    private final AnalysisResultReadyTracker readyTracker;
    private final ProductUsageService usage;

    @EventListener
    public void beforeWorkChange(ProductUsageBeforeWorkChange event) { settle(event.workId()); }

    public void beforeWorkPurge(UUID workId) {
        settle(workId);
        usage.purgeWork(workId);
    }

    private void settle(UUID workId) {
        if (!usage.isActive()) return;
        if (em.find(Work.class, workId, LockModeType.PESSIMISTIC_WRITE) == null) return;
        // 모든 business 잠금을 먼저 얻는다. 집계 상태 잠금에서 Job 잠금으로 돌아가지 않는다.
        var jobs = em.createQuery("select j from AnalysisJob j where j.work.id = :work order by j.id", AnalysisJob.class)
                .setParameter("work", workId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        jobs.forEach(readyTracker::reconcileLocked);
        em.flush();
        usage.settleWork(workId);
    }
}
