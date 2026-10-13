package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class EpisodePurgeDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public int deleteChunks(UUID episodeId) {
        int deleted = jdbcTemplate.update("delete from retrieval_chunks where episode_id = ?", episodeId);
        deleted += jdbcTemplate.update("delete from retrieval_sources where episode_id = ?", episodeId);
        return deleted + jdbcTemplate.update("delete from episode_chunks where episode_id = ?", episodeId);
    }
}
