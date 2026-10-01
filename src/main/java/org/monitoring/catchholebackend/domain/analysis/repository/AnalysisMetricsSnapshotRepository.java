package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.event.AnalysisMetricsEvent.Labels;
import org.monitoring.catchholebackend.domain.analysis.type.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class AnalysisMetricsSnapshotRepository {
    private final EntityManager em;
    public record Row(Labels labels, String queueState, long count, LocalDateTime oldest) {}
    @Transactional(readOnly = true)
    public List<Row> read() {
        List<Row> result = new ArrayList<>();
        String queue = "case when " + AnalysisJobEligibility.DOMAIN + " then 'eligible' else 'dependency_blocked' end";
        // CASE를 다시 펼치면 서브쿼리 SQL 별칭이 달라져 PostgreSQL이 같은 그룹 식으로 인식하지 못한다.
        // 선택 별칭으로 원래 CASE를 참조하여 중복 확장을 막는다.
        for (Object[] r : em.createQuery("select job.jobType, job.analysisMode, job.reviewMode, " + queue
                + " as queueState, count(job), min(coalesce(job.pendingSince, job.createdAt)) from AnalysisJob job "
                + "where job.status = 'PENDING' and job.work.lifecycleStatus = 'ACTIVE' group by job.jobType, job.analysisMode, job.reviewMode, queueState", Object[].class).getResultList())
            result.add(new Row(new Labels((AnalysisJobType)r[0], (AnalysisMode)r[1], (AnalysisReviewMode)r[2]), (String)r[3], ((Number)r[4]).longValue(), (LocalDateTime)r[5]));
        for (Object[] r : em.createQuery("select job.jobType, job.analysisMode, job.reviewMode, count(job), min(job.startedAt) "
                + "from AnalysisJob job where job.status = 'RUNNING' and job.work.lifecycleStatus = 'ACTIVE' "
                + "group by job.jobType, job.analysisMode, job.reviewMode", Object[].class).getResultList())
            result.add(new Row(new Labels((AnalysisJobType)r[0], (AnalysisMode)r[1], (AnalysisReviewMode)r[2]), "running", ((Number)r[3]).longValue(), (LocalDateTime)r[4]));
        return List.copyOf(result);
    }
}
