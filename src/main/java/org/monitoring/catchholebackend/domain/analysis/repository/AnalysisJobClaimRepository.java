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
    private final org.monitoring.catchholebackend.domain.validation.processor.ValidationSettingsLock settingsLock;

    // 작품의 부분 관계 결과는 inline 관계 분석을 맡을 때만 새 Worker를 요구한다.
    // 활성 백필의 이관 조건은 deferInlineRelationshipAnalysis와 같으며 Work 잠금 뒤 다시 검사한다.
    // 이미 Job에 고정된 partial 계약 보호는 이 예외와 별개로 유지한다.
    private static final String ELIGIBLE = """
            job.jobType <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.RELATIONSHIP_EXTRACTION
            and job.status = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING
            and job.jobType in :jobTypes and job.analysisMode in :modes and job.sourceKind in :sourceKinds
            and (:supportsCharacterComparisonGroups = true or job.characterComparisonInputHash is null)
            and (:supportsRelationshipAnalysis = true or job.relationshipAnalysisEnabled = false)
            and (:supportsRelationshipPartialResults = true or job.relationshipPartialResultsEnabled = false)
            and (:supportsRelationshipPartialResults = true
                or job.jobType <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                or job.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
                or exists (select deferred.workId from RelationshipBackfillWork deferred
                    where deferred.workId = job.work.id
                    and deferred.state <> org.monitoring.catchholebackend.domain.relationship.type.RelationshipBackfillWorkState.COMPLETE)
                or not exists (select a.id from RelationshipGraphAnalysis a
                    where a.workId = job.work.id
                    and a.status = org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract$AnalysisStatus.APPLIED
                    and a.failureCode = 'PARTIAL_COMPARISON_FAILED'
                    and ((a.generation = 0 and not exists (select b.workId from RelationshipBackfillWork b where b.workId = job.work.id))
                        or exists (select b.workId from RelationshipBackfillWork b where b.workId = job.work.id
                            and (a.generation = b.publishedGeneration or a.generation = b.buildingGeneration)))))
            and
            """ + AnalysisJobEligibility.DOMAIN + " and " + AnalysisJobEligibility.VALIDATION_LOCK;

    public Optional<AnalysisJob> findClaimableJob(WorkerAnalysisJobClaimRequest request) {
        if (request.effectiveSupportedAnalysisModes().isEmpty() || request.effectiveSupportedSourceKinds().isEmpty()) {
            return Optional.empty();
        }
        var window = settingsLock.window();
        // 회차가 많이 대기한 한 작품이 다른 작품을 scan 한도 밖으로 밀지 않도록 작품 단위로 고른다.
        List<UUID> workIds = entityManager.createQuery("select job.work.id from AnalysisJob job where "
                        + ELIGIBLE + " group by job.work.id order by min(job.createdAt), job.work.id", UUID.class)
                .setParameter("jobTypes", request.allowedJobTypes())
                .setParameter("modes", request.effectiveSupportedAnalysisModes())
                .setParameter("sourceKinds", request.effectiveSupportedSourceKinds())
                .setParameter("supportsCharacterComparisonGroups", Boolean.TRUE.equals(request.supportsCharacterComparisonGroups()))
                .setParameter("supportsRelationshipAnalysis", Boolean.TRUE.equals(request.supportsRelationshipAnalysis()))
                .setParameter("supportsRelationshipPartialResults", Boolean.TRUE.equals(request.supportsRelationshipPartialResults()))
                .setParameter("lockNow", window.now())
                .setParameter("lockQueuedCutoff", window.queuedCutoff())
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
            // 잠금을 얻기 전에 생성된 조회 snapshot으로 판단하지 않는다. 검수 접수도 Work 잠금 아래라 설정 잠금 조건도 여기서 다시 본다.
            var lockedWindow = settingsLock.window();
            List<AnalysisJob> jobs = entityManager.createQuery("select job from AnalysisJob job where "
                            + ELIGIBLE + " and job.work.id = :workId order by job.createdAt, job.runSequence, job.id",
                            AnalysisJob.class)
                    .setParameter("jobTypes", request.allowedJobTypes())
                    .setParameter("modes", request.effectiveSupportedAnalysisModes())
                    .setParameter("sourceKinds", request.effectiveSupportedSourceKinds())
                    .setParameter("supportsCharacterComparisonGroups", Boolean.TRUE.equals(request.supportsCharacterComparisonGroups()))
                    .setParameter("supportsRelationshipAnalysis", Boolean.TRUE.equals(request.supportsRelationshipAnalysis()))
                    .setParameter("supportsRelationshipPartialResults", Boolean.TRUE.equals(request.supportsRelationshipPartialResults()))
                    .setParameter("workId", workId)
                    .setParameter("lockNow", lockedWindow.now())
                    .setParameter("lockQueuedCutoff", lockedWindow.queuedCutoff())
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
