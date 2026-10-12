package org.monitoring.catchholebackend.domain.settingtool.repository;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Python 소유 청크 테이블에서 소속만 검증한다. 본문·embedding을 읽거나 변경하지 않는다. */
@Repository
@RequiredArgsConstructor
public class SettingToolChunkRepository {
    private final JdbcTemplate jdbc;

    public Optional<UUID> findEpisodeId(UUID chunkId) {
        return jdbc.query("select episode_id from episode_chunks where id = ?",
                (row, index) -> row.getObject("episode_id", UUID.class), chunkId).stream().findFirst();
    }
}
