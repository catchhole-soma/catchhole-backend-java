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
                select work_id, source_content_s3_key, source_content_s3_version, source_content_hash
                from analysis_jobs where id = ?
                """, (rs, row) -> new Source(rs.getObject("work_id", UUID.class),
                rs.getString("source_content_s3_key"), rs.getString("source_content_s3_version"),
                rs.getString("source_content_hash")), jobId).stream().findFirst().orElse(null);
    }

    public record Source(UUID workId, String key, String version, String hash) {}
}
