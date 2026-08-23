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
        return jdbcTemplate.update("delete from episode_chunks where episode_id = ?", episodeId);
    }
}
