package org.monitoring.catchholebackend.domain.work.repository;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkPurgeDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<UUID> findUploadBatchIds(UUID workId) {
        return jdbcTemplate.queryForList(
                "select id from upload_batches where work_id = ?",
                UUID.class,
                workId
        );
    }

    /**
     * 작품 FK 그래프를 자식부터 명시적으로 삭제한다. 이 메서드는 반드시 하나의 트랜잭션 안에서 호출한다.
     */
    public WorkPurgeDatabaseResult purgeWorkData(UUID workId) {
        int deletedCount = 0;
        // GH312: 검토 종료 receipt는 validation_reports와 analysis_jobs를 모두 참조하므로 둘보다 먼저 지운다.
        deletedCount += update("delete from validation_review_completions where work_id = ?", workId);
        for (String table : List.of("validation_judgment_history","validation_judgments","validation_check_history","validation_evidence","validation_findings","validation_checkpoints","validation_publications","validation_checks","validation_stages"))
            deletedCount += update("delete from " + table + " where report_id in (select id from validation_reports where work_id = ?)",workId);
        deletedCount += update("delete from ai_token_usages where validation_job_id in (select j.id from validation_jobs j join validation_reports r on r.id=j.report_id where r.work_id = ?)",workId);
        deletedCount += update("delete from validation_jobs where report_id in (select id from validation_reports where work_id = ?)",workId);
        deletedCount += update("delete from validation_corrections where work_id = ?", workId);
        deletedCount += update("delete from validation_baseline_sources where report_id in (select id from validation_reports where work_id = ?)", workId);
        deletedCount += update("delete from validation_request_keys where work_id = ?", workId);
        deletedCount += update("delete from validation_reports where work_id = ?", workId);
        deletedCount += update("delete from relationship_processing_states where work_id = ?", workId);
        deletedCount += update("""
                delete from relationship_evidence
                where candidate_id in (select id from relationship_candidates where work_id = ?)
                """, workId);
        deletedCount += update("delete from relationship_candidates where work_id = ?", workId);
        deletedCount += update("delete from relationship_mention_groups where work_id = ?", workId);
        deletedCount += update("delete from character_relationships where work_id = ?", workId);
        deletedCount += update("""
                delete from character_snapshot_sources
                where character_id in (select id from characters where work_id = ?)
                """, workId);
        deletedCount += update("""
                delete from character_facts
                where character_id in (select id from characters where work_id = ?)
                """, workId);
        deletedCount += update("delete from world_setting_candidates where work_id = ?", workId);
        deletedCount += update("delete from setting_candidates where work_id = ?", workId);
        deletedCount += update("""
                delete from analysis_job_episode_targets
                where analysis_job_id in (select id from analysis_jobs where work_id = ?)
                """, workId);
        deletedCount += update("delete from ai_token_usages where work_id = ?", workId);
        deletedCount += update("""
                delete from retrieval_chunks where episode_id in
                (select episode_id from retrieval_sources where work_id = ?)
                """, workId);
        deletedCount += update("delete from retrieval_sources where work_id = ?", workId);
        deletedCount += update("delete from chat_runs where work_id = ?", workId);
        deletedCount += update("delete from chat_questions where work_id = ?", workId);
        deletedCount += update("delete from chat_sessions where work_id = ?", workId);
        deletedCount += update("""
                delete from setting_book_chunks
                where analysis_job_id in (select id from analysis_jobs where work_id = ?)
                """, workId);
        deletedCount += update("delete from analysis_jobs where work_id = ?", workId);
        deletedCount += update("delete from world_settings where work_id = ?", workId);
        deletedCount += update("delete from character_setting_schemas where work_id = ?", workId);
        deletedCount += update("delete from characters where work_id = ?", workId);
        deletedCount += update("delete from episode_source_purge_requests where work_id = ?", workId);
        deletedCount += update("""
                delete from episode_chunks
                where episode_id in (select id from episodes where work_id = ?)
                """, workId);
        deletedCount += update("delete from episodes where work_id = ?", workId);
        // Revisions reference their book while the book points at its current revision. Break that cycle after sources/jobs.
        update("""
                update upload_files set current_setting_book_revision_id = null
                where batch_id in (select id from upload_batches where work_id = ?)
                """, workId);
        deletedCount += update("""
                delete from setting_book_revisions
                where setting_book_id in (
                    select id from upload_files where batch_id in (select id from upload_batches where work_id = ?)
                )
                """, workId);
        deletedCount += update("""
                delete from upload_files
                where batch_id in (select id from upload_batches where work_id = ?)
                """, workId);
        deletedCount += update("delete from upload_batches where work_id = ?", workId);
        deletedCount += update("delete from works where id = ?", workId);
        return new WorkPurgeDatabaseResult(deletedCount, deletedCount, 0);
    }

    private int update(String sql, UUID workId) {
        return jdbcTemplate.update(sql, workId);
    }
}
