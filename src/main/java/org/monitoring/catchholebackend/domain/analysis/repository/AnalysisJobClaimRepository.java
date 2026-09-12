package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.WorkerAnalysisJobClaimRequest;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.springframework.stereotype.Repository;

/** 작품 row를 짧게 잠근 뒤 새 statement에서 실행 조건을 재확인해 여러 Worker의 진입을 직렬화한다. */
@Repository
@RequiredArgsConstructor
public class AnalysisJobClaimRepository {

    private final EntityManager entityManager;

    private static final String ELIGIBLE = """
            job.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING
            and job.jobType in :jobTypes and job.analysisMode in :modes
            and job.work.lifecycleStatus = org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus.ACTIVE
            and (job.analysisMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.CONFIRMED_ONLY
                or (job.journalStatus = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.PENDING
                    and (job.runSequence = 0 or exists (
                        select predecessor.id from AnalysisJob predecessor
                        where predecessor.id = job.predecessorJobId
                          and predecessor.analysisRunId = job.analysisRunId
                          and predecessor.runGeneration = job.runGeneration
                          and predecessor.work.id = job.work.id
                          and predecessor.runSequence = job.runSequence - 1
                          and predecessor.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.SUCCEEDED
                          and predecessor.journalStatus = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.SEALED
                          and (predecessor.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.MANUAL
                               or predecessor.automaticAppliedAt is not null)
                    ))))
            and (job.jobType <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                or not exists (
                    select running.id from AnalysisJob running where running.work.id = job.work.id
                      and running.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                      and running.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                ))
            """;

    public Optional<AnalysisJob> findClaimableJob(WorkerAnalysisJobClaimRequest request) {
        // 회차가 많이 대기한 한 작품이 다른 작품을 scan 한도 밖으로 밀지 않도록 작품 단위로 고른다.
        List<UUID> workIds = entityManager.createQuery("select job.work.id from AnalysisJob job where "
                        + ELIGIBLE + " group by job.work.id order by min(job.createdAt), job.work.id", UUID.class)
                .setParameter("jobTypes", request.allowedJobTypes())
                .setParameter("modes", request.effectiveSupportedAnalysisModes())
                .setMaxResults(100)
                .getResultList();
        for (UUID workId : workIds) {
            List<Work> locked = entityManager.createQuery("select work from Work work where work.id = :id", Work.class)
                    .setParameter("id", workId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", -2)
                    .getResultList();
            if (locked.isEmpty()) {
                continue;
            }
            // 잠금을 얻기 전에 생성된 조회 snapshot으로 판단하지 않는다.
            List<AnalysisJob> jobs = entityManager.createQuery("select job from AnalysisJob job where "
                            + ELIGIBLE + " and job.work.id = :workId order by job.createdAt, job.runSequence, job.id",
                            AnalysisJob.class)
                    .setParameter("jobTypes", request.allowedJobTypes())
                    .setParameter("modes", request.effectiveSupportedAnalysisModes())
                    .setParameter("workId", workId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setMaxResults(1)
                    .getResultList();
            if (!jobs.isEmpty()) {
                return Optional.of(jobs.getFirst());
            }
        }
        return Optional.empty();
    }
}
