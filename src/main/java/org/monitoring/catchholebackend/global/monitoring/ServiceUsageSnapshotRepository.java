package org.monitoring.catchholebackend.global.monitoring;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ServiceUsageSnapshotRepository {
    private final EntityManager em;

    public record Snapshot(long members, long analysisUsers7d, long requests24h,
            double averageEpisodes24h, boolean requestHistoryComplete,
            long works, long episodes, long pendingFeedbackRequests) {}

    @Transactional(readOnly = true)
    public Snapshot read(LocalDateTime asOf) {
        // 기간 인덱스로 후보를 좁힌 뒤 요청 ID 인덱스로 전체 원본을 확인한다.
        // 기간 안의 회차만 먼저 묶으면 경계 밖의 잘못된 접수 시각/대상 수를 놓칠 수 있다.
        Object[] row = (Object[]) em.createNativeQuery("""
                with active_owners as (
                    select work.id as work_id, work.member_id
                    from works work join members member on member.id = work.member_id
                    where work.lifecycle_status = 'ACTIVE' and member.status in ('ACTIVE', 'SUSPENDED')
                ), activity_jobs as (
                    select work_id from analysis_jobs
                    where job_type in ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
                      and created_at >= :week_start and created_at < :as_of
                    union
                    select work_id from analysis_jobs
                    where job_type in ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
                      and attempt_requested_at >= :week_start and attempt_requested_at < :as_of
                ), recent_created_jobs as (
                    select job.metrics_request_id, job.metrics_request_started_at,
                           job.metrics_request_episode_count, job.metrics_user_retry,
                           job.metrics_attempt_no, job.analysis_mode
                    from analysis_jobs job
                    join active_owners owner on owner.work_id = job.work_id
                    where job.job_type in ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
                      and job.created_at >= :day_start and job.created_at < :as_of
                ), candidate_request_ids as (
                    select metrics_request_id from analysis_jobs
                    where job_type in ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
                      and metrics_request_id is not null
                      and metrics_request_started_at >= :day_start and metrics_request_started_at < :as_of
                      and (metrics_user_retry = false or metrics_attempt_no > 1)
                    union
                    select metrics_request_id from recent_created_jobs
                    where metrics_request_id is not null
                      and (metrics_user_retry = false or metrics_attempt_no > 1)
                ), request_groups as (
                    select job.metrics_request_id, min(job.metrics_request_started_at) as requested_at,
                           min(job.metrics_request_episode_count) as episodes,
                           case when count(job.metrics_request_started_at) = count(*)
                                  and min(job.metrics_request_started_at) = max(job.metrics_request_started_at)
                                  and min(job.metrics_request_started_at) < :as_of
                                  and count(job.metrics_request_episode_count) = count(*)
                                  and min(job.metrics_request_episode_count) = max(job.metrics_request_episode_count)
                                  and min(job.metrics_request_episode_count) > 0
                                  and count(distinct job.episode_id) = min(job.metrics_request_episode_count)
                                  and count(*) = count(distinct job.episode_id)
                                  and count(distinct job.work_id) = 1
                                  and count(distinct owner.member_id) = 1
                                  and count(distinct job.job_type) = 1
                                  -- V70의 run UUID 복원은 계측 전 첫 재시도일 수도 있다.
                                  -- 이후 attempt 번호가 커져도 최초 접수 증거로 바꾸지 않는다.
                                  and sum(case when job.metrics_request_id = job.analysis_run_id
                                                and job.metrics_user_retry = true then 1 else 0 end) = 0
                                then 1 else 0 end as valid
                    from candidate_request_ids candidate
                    join analysis_jobs job on job.metrics_request_id = candidate.metrics_request_id
                    join active_owners owner on owner.work_id = job.work_id
                    where job.job_type in ('SETTING_EXTRACTION', 'EPISODE_VALIDATION')
                      and (job.metrics_user_retry = false or job.metrics_attempt_no > 1)
                    group by job.metrics_request_id
                ), valid_recent_requests as (
                    select episodes from request_groups
                    where valid = 1 and requested_at >= :day_start and requested_at < :as_of
                )
                select
                    (select count(*) from members where status in ('ACTIVE', 'SUSPENDED')),
                    (select count(distinct owner.member_id) from activity_jobs activity
                     join active_owners owner on owner.work_id = activity.work_id),
                    (select count(*) from valid_recent_requests),
                    (select avg(cast(episodes as double precision)) from valid_recent_requests),
                    (not exists (select 1 from request_groups where valid = 0)
                     and not exists (
                         select 1 from recent_created_jobs
                         where ((metrics_user_retry = false or metrics_attempt_no > 1)
                                and (metrics_request_id is null or metrics_request_started_at is null
                                     or metrics_request_episode_count is null or metrics_request_episode_count <= 0))
                            or (analysis_mode = 'ORDERED_PROVISIONAL'
                                and metrics_user_retry = true and metrics_attempt_no = 1)
                     )),
                    (select count(*) from active_owners),
                    (select count(*) from episodes episode
                     join active_owners owner on owner.work_id = episode.work_id
                     where episode.status <> 'ARCHIVED'),
                    (select count(*) from ai_token_extension_requests request
                     join members member on member.id = request.member_id
                     where request.status = 'PENDING' and member.status in ('ACTIVE', 'SUSPENDED'))
                """)
                .setParameter("week_start", asOf.minusDays(7))
                .setParameter("day_start", asOf.minusDays(1))
                .setParameter("as_of", asOf)
                .setHint("jakarta.persistence.query.timeout", 5000)
                .getSingleResult();
        return new Snapshot(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(), row[3] == null ? Double.NaN : ((Number) row[3]).doubleValue(),
                (Boolean) row[4], ((Number) row[5]).longValue(), ((Number) row[6]).longValue(),
                ((Number) row[7]).longValue());
    }
}
