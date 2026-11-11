package org.monitoring.catchholebackend.domain.productusage.repository;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.productusage.dto.ProductUsageCompletion;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository @RequiredArgsConstructor
public class ProductUsageSourceRepository {
    private final EntityManager em;

    @Transactional(readOnly = true)
    public List<ProductUsageCompletion> completedRequests(LocalDateTime asOf, UUID workId) {
        // 작품 정산에서도 요청의 전체 원본을 검사한다. 작품 조건을 current_jobs에 적용하면
        // 잘못 공유된 요청 ID의 다른 작품 기록을 숨기게 된다.
        String workFilter = workId == null ? "" : " and work_id = :work";
        var query = em.createNativeQuery("""
                with candidates as (
                    select distinct metrics_request_id
                    from analysis_jobs
                    where metrics_request_id is not null and job_type = 'SETTING_EXTRACTION'
                    %s
                ), current_jobs as (
                    select job.*, work.member_id, member.created_at as member_created_at,
                           row_number() over (
                               partition by job.metrics_request_id, job.source_kind, job.episode_id,
                                            job.source_setting_book_id, job.job_type
                               order by job.created_at desc, job.id desc
                           ) as latest
                    from analysis_jobs job
                    join candidates candidate on candidate.metrics_request_id = job.metrics_request_id
                    join works work on work.id = job.work_id
                    join members member on member.id = work.member_id
                    where not exists (
                        select 1 from product_usage_analysis_receipts receipt
                        where receipt.request_id = job.metrics_request_id
                    )
                )
                select cast(metrics_request_id as varchar), min(cast(work_id as varchar)), min(member_id),
                       min(member_created_at), min(metrics_request_started_at), max(result_ready_at)
                from current_jobs
                where latest = 1
                group by metrics_request_id
                having count(distinct work_id) = 1 and count(distinct member_id) = 1
                   and count(distinct source_kind) = 1
                   and count(*) = min(metrics_request_episode_count)
                   and count(metrics_request_episode_count) = count(*)
                   and min(metrics_request_episode_count) = max(metrics_request_episode_count)
                   and count(metrics_request_started_at) = count(*)
                   and min(metrics_request_started_at) = max(metrics_request_started_at)
                   and sum(case when job_type = 'SETTING_EXTRACTION'
                                and ((source_kind = 'EPISODE' and episode_id is not null and source_setting_book_id is null)
                                  or (source_kind = 'SETTING_BOOK' and episode_id is null and source_setting_book_id is not null))
                                and status = 'SUCCEEDED' and result_ready_at >= metrics_request_started_at
                                and result_outcome in ('success', 'partial_success')
                                and (analysis_mode <> 'ORDERED_PROVISIONAL' or journal_status = 'SEALED')
                                and (review_mode <> 'AUTOMATIC' or automatic_applied_at is not null)
                            then 1 else 0 end) = count(*)
                   and max(result_ready_at) < :as_of
                order by max(result_ready_at), metrics_request_id
                """.formatted(workFilter)).setParameter("as_of", asOf)
                .setHint("jakarta.persistence.query.timeout", 5000);
        if (workId != null) query.setParameter("work", workId);
        List<?> rows = query.getResultList();
        return rows.stream().map(value -> {
            Object[] row = (Object[]) value;
            return new ProductUsageCompletion(uuid(row[0]), uuid(row[1]), ((Number) row[2]).longValue(),
                    time(row[3]), time(row[4]), time(row[5]));
        }).toList();
    }

    private UUID uuid(Object value) { return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()); }
    private LocalDateTime time(Object value) {
        return value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }
}
