package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lists must not initialize AnalysisJob's large frozen JSON to read a source manifest. */
@Repository
@RequiredArgsConstructor
public class GeneralEvidenceSourceRepository {
    private final JdbcTemplate jdbc;

    public Source find(UUID jobId) {
        return jdbc.query("""
                select j.work_id, j.source_content_s3_key, j.source_content_s3_version, j.source_content_hash,
                       (j.source_reference_invalidated_at is not null or (j.source_kind='EPISODE' and
                        (e.id is null or e.work_id<>j.work_id or e.status='ARCHIVED' or (j.source_content_s3_key is not null and
                         (e.content_s3_key is distinct from j.source_content_s3_key
                          or e.content_hash is distinct from j.source_content_hash
                          or e.content_s3_version is distinct from j.source_content_s3_version))
                         or exists(select 1 from episode_source_purge_requests p where p.episode_id=e.id
                            and p.retained_content_key is distinct from j.source_content_s3_key)))) as invalidated
                from analysis_jobs j left join episodes e on e.id=j.episode_id where j.id = ?
                """, (rs, row) -> new Source(rs.getObject("work_id", UUID.class),
                rs.getString("source_content_s3_key"), rs.getString("source_content_s3_version"),
                rs.getString("source_content_hash"), rs.getBoolean("invalidated")), jobId).stream().findFirst().orElse(null);
    }

    public record Source(UUID workId, String key, String version, String hash, boolean invalidated) {
        public Source(UUID workId, String key, String version, String hash) { this(workId,key,version,hash,false); }
    }
}
