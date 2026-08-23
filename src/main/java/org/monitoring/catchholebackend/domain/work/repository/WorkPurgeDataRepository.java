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
