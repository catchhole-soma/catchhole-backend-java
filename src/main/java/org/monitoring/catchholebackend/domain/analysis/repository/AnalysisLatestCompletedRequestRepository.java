package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class AnalysisLatestCompletedRequestRepository {
    private final EntityManager em;

    public record CompletedRequest(LocalDateTime requestedAt, LocalDateTime completedAt, long episodeCount) {}

    @Transactional(readOnly = true)
    public Optional<CompletedRequest> readLatest() {
        // 재시도가 만든 새 Job은 같은 요청·회차의 이전 실패를 대신한다. 완료 시각으로 최신 요청을 고른다.
        List<?> rows = em.createNativeQuery("""
                with current_jobs as (
                    select job.*, row_number() over (
                        partition by job.metrics_request_id, job.episode_id, job.job_type
                        order by job.created_at desc, job.id desc
                    ) as latest
                    from analysis_jobs job join works work on work.id = job.work_id
                    where job.metrics_request_id is not null
                      and job.job_type in ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
                      and work.lifecycle_status = 'ACTIVE'
                )
                select min(metrics_request_started_at), max(result_ready_at), count(*)
                from current_jobs
                where latest = 1
                group by metrics_request_id
                having count(*) = min(metrics_request_episode_count)
                   and count(metrics_request_episode_count) = count(*)
                   and min(metrics_request_episode_count) = max(metrics_request_episode_count)
                   and count(metrics_request_started_at) = count(*)
                   and min(metrics_request_started_at) = max(metrics_request_started_at)
                   and sum(case when status = 'SUCCEEDED' and result_ready_at is not null
                                and result_outcome in ('success', 'partial_success')
                                and (analysis_mode <> 'ORDERED_PROVISIONAL' or journal_status = 'SEALED')
                                and (review_mode <> 'AUTOMATIC' or automatic_applied_at is not null)
                            then 1 else 0 end) = count(*)
                order by max(result_ready_at) desc, metrics_request_id desc
                """).setMaxResults(1).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new CompletedRequest(time(row[0]), time(row[1]), ((Number) row[2]).longValue()));
    }

    private LocalDateTime time(Object value) {
        return value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }
}
