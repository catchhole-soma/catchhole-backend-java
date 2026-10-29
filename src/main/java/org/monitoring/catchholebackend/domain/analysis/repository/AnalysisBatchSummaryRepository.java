package org.monitoring.catchholebackend.domain.analysis.repository;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisBatchSettingBookStatus;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.springframework.stereotype.Repository;

/** 현재 페이지의 최신 대상 선택과 표시 상태를 DB에서 계산한다. 큰 분석 JSON은 읽지 않는다. */
@Repository
@RequiredArgsConstructor
public class AnalysisBatchSummaryRepository {
    private final EntityManager em;

    public record GroupSummary(UUID batchId, AnalysisJobType jobType, boolean episodeOnly,
            AnalysisBatchStatus status, int total, int pending, int running, int succeeded, int failed,
            int canceled, List<UUID> currentJobIds, LocalDateTime lastActivityAt,
            Integer episodeStartNo, Integer episodeEndNo, int episodeCount) {}
    public record BookSummary(UUID pageBatchId, UUID settingBookId, UUID sourceAnalysisJobId, UUID batchId,
            UUID revisionId, String settingBookName, AnalysisBatchSettingBookStatus status,
            AnalysisFailureCode failureCode, String currentStep, boolean changed, boolean historical,
            LocalDateTime lastActivityAt) {}
    private record GroupKey(UUID batchId, AnalysisJobType jobType, boolean episodeOnly) {}

    // snapshot 연결이 있으면 episode_id보다 우선한다. legacy 다회차 Job은 회차마다 한 행이다.
    private static final String CURRENT_TARGETS = """
            with public_jobs as (
                select j.id, j.batch_id, j.episode_id, j.job_type, j.source_kind,
                       j.source_setting_book_id, j.status, j.analysis_mode, j.analysis_run_id,
                       j.run_generation, j.run_sequence, j.journal_status, j.review_mode,
                       j.automatic_applied_at, j.review_application_held_at, j.failure_code, j.checkpoint_stage, j.created_at, j.updated_at
                from analysis_jobs j
                where j.work_id = :workId and j.batch_id in (:batchIds)
                  and j.job_type not in ('WORLD_SETTING_COMPARISON', 'CHARACTER_FACT_COMPARISON')
            ), expanded as (
                select j.id, j.batch_id, j.job_type, j.source_kind, j.status, j.analysis_mode,
                       j.analysis_run_id, j.run_generation, j.run_sequence, j.journal_status,
                       j.review_mode, j.automatic_applied_at, j.review_application_held_at, j.failure_code, j.checkpoint_stage,
                       j.created_at, j.updated_at,
                       case when j.source_kind = 'SETTING_BOOK' then j.source_setting_book_id
                            else coalesce(t.episode_id, j.episode_id) end as target_id,
                       e.episode_no
                from public_jobs j
                left join analysis_job_episode_targets t
                  on t.analysis_job_id = j.id and j.source_kind = 'EPISODE'
                left join episodes e on j.source_kind = 'EPISODE'
                  and e.id = coalesce(t.episode_id, j.episode_id)
            ), ranked as (
                select e.*, row_number() over (
                    partition by batch_id, job_type, source_kind, target_id
                    order by created_at desc, id desc) as latest
                from expanded e
            ), selected as (
                select r.* from ranked r where latest = 1
                  and (target_id is not null or not exists (
                      select 1 from ranked other
                      where other.batch_id = r.batch_id and other.job_type = r.job_type
                        and other.target_id is not null))
            ), normalized as (
                select s.*,
                       case when analysis_mode = 'ORDERED_PROVISIONAL' and journal_status = 'INVALIDATED'
                            then 'CANCELED'
                            when analysis_mode = 'ORDERED_PROVISIONAL' and status = 'SUCCEEDED'
                              and (journal_status is null or journal_status <> 'SEALED'
                                or (review_mode = 'AUTOMATIC' and automatic_applied_at is null
                                    and review_application_held_at is null))
                            then 'FAILED' else status end as listed_status,
                       case when analysis_mode = 'CONFIRMED_ONLY' and job_type = 'SETTING_EXTRACTION'
                              and status = 'FAILED' and failure_code = 'AI_TOKEN_QUOTA_EXHAUSTED'
                              and checkpoint_stage in ('WORLD_CANDIDATES_PUBLISHED', 'WORLD_COMPARISONS_FINISHED')
                            then 1 else 0 end as resumable_token
                from selected s
            ), ready as (
                select n.*, case when n.analysis_mode = 'ORDERED_PROVISIONAL' and n.listed_status = 'PENDING'
                    and exists (select 1 from normalized earlier
                        where earlier.batch_id = n.batch_id and earlier.job_type = n.job_type
                          and earlier.analysis_mode = 'ORDERED_PROVISIONAL'
                          and earlier.analysis_run_id = n.analysis_run_id
                          and earlier.run_generation = n.run_generation
                          and earlier.run_sequence < n.run_sequence
                          and earlier.listed_status in ('FAILED', 'CANCELED'))
                    then 1 else 0 end as blocked
                from normalized n
            ), scopes as (select 'ALL' as scope union all select 'EPISODE' as scope), scoped as (
                select scopes.scope, ready.* from ready cross join scopes
                where scopes.scope = 'ALL' or ready.source_kind = 'EPISODE'
            )
            """;

    public record ValidationRow(UUID batchId, org.monitoring.catchholebackend.domain.validation.dto.response.ValidationRequestSummaryResponse response,
            LocalDateTime updatedAt) {}
    /**
     * 현재 검수 report와 검수 단계 계산에 필요한 실행·검토 종료·release Job·회차 전체 확정 상태를 한 SQL로 읽는다(GH312 S4).
     * 단계 계산은 원고 목록·단건 조회와 같은 {@code ValidationReviewStages.resolve}다.
     */
    public List<ValidationRow> readValidations(UUID workId, List<UUID> batchIds) {
        if (batchIds.isEmpty()) return List.of();
        List<ValidationRow> result = new ArrayList<>();
        for (Object[] row : query("""
                select r.batch_id, r.id, r.episode_id, r.source_episode_no, r.status, r.baseline_status, r.hold_reason, r.updated_at,
                       vj.status, c.id, aj.status, aj.journal_status, a.status
                from validation_reports r
                left join validation_jobs vj on vj.report_id = r.id
                left join validation_review_completions c on c.report_id = r.id
                left join analysis_jobs aj on aj.id = c.extraction_job_id
                left join validation_settings_applications a on a.report_id = r.id
                where r.is_current = true and r.work_id = :workId and r.batch_id in (:batchIds)
                order by r.created_at desc, r.id desc
                """, workId, batchIds)) {
            var status = org.monitoring.catchholebackend.domain.validation.type.ValidationReportStatus.valueOf((String) row[4]);
            var baseline = org.monitoring.catchholebackend.domain.validation.type.ValidationPreparationStatus.valueOf((String) row[5]);
            boolean completed = row[9] != null;
            var stage = org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.resolve(
                    new org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.Input(true, status, baseline,
                            row[8] == null ? null : org.monitoring.catchholebackend.domain.validation.type.ValidationExecutionStatus.valueOf((String) row[8]), completed,
                            !completed ? org.monitoring.catchholebackend.domain.validation.type.ValidationExtractionState.NOT_STARTED
                                    : org.monitoring.catchholebackend.domain.validation.processor.ValidationReviewStages.extractionState(
                                            row[10] == null ? null : org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.valueOf((String) row[10]),
                                            row[11] == null ? null : org.monitoring.catchholebackend.domain.analysis.type.AnalysisJournalStatus.valueOf((String) row[11])),
                            !completed || row[12] == null ? null : org.monitoring.catchholebackend.domain.validation.type.ValidationApplicationStatus.valueOf((String) row[12])));
            result.add(new ValidationRow(uuid(row[0]), new org.monitoring.catchholebackend.domain.validation.dto.response.ValidationRequestSummaryResponse(
                    uuid(row[1]), uuid(row[2]), number(row[3]), status, baseline, (String) row[6], stage), time(row[7])));
        }
        return result;
    }

    public List<GroupSummary> readSummaries(UUID workId, List<UUID> batchIds) {
        if (batchIds.isEmpty()) return List.of();
        Map<GroupKey, List<UUID>> ids = new HashMap<>();
        for (Object[] row : query(CURRENT_TARGETS + """
                select distinct batch_id, job_type, scope, id, created_at from scoped
                order by batch_id, job_type, scope, created_at desc, id desc
                """, workId, batchIds)) {
            ids.computeIfAbsent(key(row), ignored -> new ArrayList<>()).add(uuid(row[3]));
        }
        List<GroupSummary> result = new ArrayList<>();
        for (Object[] row : query(CURRENT_TARGETS + """
                , batch_metadata as (
                    select batch_id, min(episode_no) as episode_start_no, max(episode_no) as episode_end_no,
                           count(distinct case when source_kind = 'EPISODE' then target_id end) as episode_count
                    from ready group by batch_id
                ), groups as (
                    select batch_id, job_type, scope, count(*) as total,
                           sum(case when listed_status = 'PENDING' then 1 else 0 end) as pending,
                           sum(case when listed_status = 'RUNNING' then 1 else 0 end) as running,
                           sum(case when listed_status = 'SUCCEEDED' then 1 else 0 end) as succeeded,
                           sum(case when listed_status = 'FAILED' then 1 else 0 end) as failed,
                           sum(case when listed_status = 'CANCELED' then 1 else 0 end) as canceled,
                           sum(case when listed_status = 'FAILED' and resumable_token = 0 then 1 else 0 end) as terminal_failed,
                           sum(case when listed_status = 'RUNNING' or (listed_status = 'PENDING' and blocked = 0)
                                    then 1 else 0 end) as active,
                           max(updated_at) as last_activity_at
                    from scoped group by batch_id, job_type, scope
                )
                select g.batch_id, g.job_type, g.scope,
                       case when g.active > 0 then 'IN_PROGRESS' when g.canceled > 0 then 'CANCELED'
                            when g.terminal_failed > 0 and g.succeeded > 0 then 'PARTIALLY_FAILED'
                            when g.terminal_failed > 0 then 'FAILED' else 'COMPLETED' end as status,
                       g.total, g.pending, g.running, g.succeeded, g.failed, g.canceled, g.last_activity_at,
                       m.episode_start_no, m.episode_end_no, m.episode_count
                from groups g join batch_metadata m on m.batch_id = g.batch_id
                """, workId, batchIds)) {
            GroupKey key = key(row);
            result.add(new GroupSummary(key.batchId(), key.jobType(), key.episodeOnly(),
                    AnalysisBatchStatus.valueOf((String) row[3]), number(row[4]), number(row[5]), number(row[6]),
                    number(row[7]), number(row[8]), number(row[9]), ids.getOrDefault(key, List.of()), time(row[10]),
                    nullableNumber(row[11]), nullableNumber(row[12]), number(row[13])));
        }
        return result;
    }

    public List<BookSummary> readSettingBooks(UUID workId, List<UUID> batchIds) {
        if (batchIds.isEmpty()) return List.of();
        List<BookSummary> result = new ArrayList<>();
        for (Object[] row : query(CURRENT_TARGETS + """
                , page_books as (
                    select distinct batch_id as page_batch_id, source_setting_book_id as book_id
                    from public_jobs where source_kind = 'SETTING_BOOK' and source_setting_book_id is not null
                    union
                    select f.batch_id as page_batch_id, f.id as book_id
                    from upload_files f join upload_batches b on b.id = f.batch_id
                    where b.work_id = :workId and f.batch_id in (:batchIds)
                      and f.file_role = 'SETTING_BOOK' and f.archived_at is null
                ), latest_book_jobs as (
                    select j.id, j.source_setting_book_id, j.source_setting_book_revision_id, j.batch_id,
                           j.status, j.analysis_mode, j.journal_status, j.review_mode, j.automatic_applied_at,
                           j.failure_code, j.current_step, j.updated_at,
                           row_number() over (partition by j.source_setting_book_id order by j.created_at desc, j.id desc) as latest
                    from analysis_jobs j where j.work_id = :workId and j.source_kind = 'SETTING_BOOK'
                      and j.job_type = 'SETTING_EXTRACTION'
                      and j.source_setting_book_id in (select book_id from page_books)
                )
                select p.page_batch_id, f.id, j.id, j.batch_id, j.source_setting_book_revision_id, f.original_filename,
                       case when j.analysis_mode = 'ORDERED_PROVISIONAL' and j.journal_status = 'INVALIDATED' then 'INVALIDATED'
                            when j.analysis_mode = 'ORDERED_PROVISIONAL' and j.status = 'SUCCEEDED' and j.journal_status = 'SEALED'
                              and (j.review_mode <> 'AUTOMATIC' or j.automatic_applied_at is not null) then 'COMPLETED'
                            when j.analysis_mode = 'ORDERED_PROVISIONAL'
                              and (j.status = 'FAILED' or (j.status = 'SUCCEEDED' and j.journal_status = 'INCOMPLETE')) then 'INTERRUPTED'
                            when j.status = 'CANCELED' then 'CANCELED' when j.status = 'FAILED' then 'FAILED'
                            when j.status = 'PENDING' then 'PENDING' else 'IN_PROGRESS' end as display_status,
                       case when j.status = 'FAILED' then coalesce(j.failure_code, 'UNEXPECTED_ERROR') else null end,
                       j.current_step,
                       case when f.current_setting_book_revision_id is null
                              or f.current_setting_book_revision_id <> j.source_setting_book_revision_id then 1 else 0 end as changed,
                       case when f.archived_at is not null or f.current_setting_book_revision_id is null
                              or f.current_setting_book_revision_id <> j.source_setting_book_revision_id then 1 else 0 end as historical,
                       j.updated_at
                from page_books p join latest_book_jobs j on j.source_setting_book_id = p.book_id and j.latest = 1
                join upload_files f on f.id = p.book_id
                order by p.page_batch_id, j.updated_at desc, j.id desc
                """, workId, batchIds)) {
            result.add(new BookSummary(uuid(row[0]), uuid(row[1]), uuid(row[2]), uuid(row[3]), uuid(row[4]),
                    (String) row[5], AnalysisBatchSettingBookStatus.valueOf((String) row[6]),
                    row[7] == null ? null : AnalysisFailureCode.valueOf((String) row[7]), (String) row[8],
                    number(row[9]) != 0, number(row[10]) != 0, time(row[11])));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> query(String sql, UUID workId, List<UUID> batchIds) {
        return em.createNativeQuery(sql).setParameter("workId", workId)
                .setParameter("batchIds", batchIds).getResultList();
    }
    private GroupKey key(Object[] row) {
        return new GroupKey(uuid(row[0]), AnalysisJobType.valueOf((String) row[1]), "EPISODE".equals(row[2]));
    }
    private UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value instanceof byte[] bytes) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        return UUID.fromString(value.toString());
    }
    private int number(Object value) { return ((Number) value).intValue(); }
    private Integer nullableNumber(Object value) { return value == null ? null : number(value); }
    private LocalDateTime time(Object value) {
        return value == null ? null : value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }
}
