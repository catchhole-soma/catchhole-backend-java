package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.type.WorkLifecycleStatus;
import org.springframework.stereotype.Repository;

/** claim 트랜잭션에서 후보 선정과 잠금 후의 회수 가능 여부 검증을 분리한다. */
@Repository
@RequiredArgsConstructor
public class AnalysisJobLeaseRecoveryRepository {

    private final EntityManager entityManager;

    public List<AnalysisJob> findExpiredLeasesForUpdate(
            Collection<AnalysisJobType> jobTypes, LocalDateTime now, int limit
    ) {
        if (jobTypes.isEmpty() || limit == 0) {
            return List.of();
        }
        // refresh가 동일 요청의 미반영 변경을 버리지 않도록 먼저 저장한다. 커밋은 호출자가 한다.
        entityManager.flush();
        // fetch join의 후속 잠금은 최초 만료 조건을 재검사하지 않는다. 여기서는 ID만 수집한다.
        List<Candidate> candidates = entityManager.createQuery("""
                        select job.id as jobId, job.work.id as workId
                        from AnalysisJob job
                        where job.status = :status and job.jobType in :jobTypes
                          and job.work.lifecycleStatus = :workStatus
                          and (job.leaseToken is null or job.leaseExpiresAt is null or job.leaseExpiresAt <= :now)
                        order by job.leaseExpiresAt asc, job.createdAt asc, job.id asc
                        """, Tuple.class)
                .setParameter("status", AnalysisJobStatus.RUNNING)
                .setParameter("jobTypes", jobTypes)
                .setParameter("workStatus", WorkLifecycleStatus.ACTIVE)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(row -> new Candidate(row.get("jobId", UUID.class), row.get("workId", UUID.class)))
                .toList();

        // 기존 claim/자동 반영과 같은 Work → Job 순서. 사용 중인 행은 다음 claim에 맡긴다.
        Set<UUID> activeWorkIds = new HashSet<>();
        for (UUID workId : candidates.stream().map(Candidate::workId).distinct().sorted().toList()) {
            List<Work> works = entityManager.createQuery("select work from Work work where work.id = :id", Work.class)
                    .setParameter("id", workId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", -2)
                    .getResultList();
            if (!works.isEmpty()) {
                Work work = works.getFirst();
                entityManager.refresh(work);
                if (work.getLifecycleStatus() == WorkLifecycleStatus.ACTIVE) {
                    activeWorkIds.add(workId);
                }
            }
        }

        Map<UUID, AnalysisJob> recoverable = new HashMap<>();
        for (Candidate candidate : candidates.stream()
                .sorted(Comparator.comparing(Candidate::workId).thenComparing(Candidate::jobId)).toList()) {
            if (!activeWorkIds.contains(candidate.workId())) {
                continue;
            }
            List<AnalysisJob> jobs = entityManager.createQuery(
                            "select job from AnalysisJob job where job.id = :id", AnalysisJob.class)
                    .setParameter("id", candidate.jobId())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", -2)
                    .getResultList();
            if (jobs.isEmpty()) {
                continue;
            }
            AnalysisJob job = jobs.getFirst();
            // 잠금 획득 전에 영속성 컨텍스트에 들어온 객체도 최신 DB 상태로 확인한다.
            // 모든 회수 부수효과는 이 조회가 끝난 뒤 시작하므로 회수 중 변경은 덮어쓰지 않는다.
            entityManager.refresh(job);
            if (job.getStatus() == AnalysisJobStatus.RUNNING && jobTypes.contains(job.getJobType())
                    && (job.getLeaseToken() == null || job.isLeaseExpired(now))) {
                recoverable.put(job.getId(), job);
            }
        }
        // 잠금은 일관된 ID 순서로 얻되, 실제 회수 순서는 최초 만료 후보 순서를 유지한다.
        return candidates.stream().map(candidate -> recoverable.get(candidate.jobId()))
                .filter(Objects::nonNull).toList();
    }

    private record Candidate(UUID jobId, UUID workId) { }
}
