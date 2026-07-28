package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {

    Optional<Episode> findByIdAndWorkId(UUID id, UUID workId);

    Optional<Episode> findByIdAndWorkIdAndStatusNot(UUID id, UUID workId, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);

    List<Episode> findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(UUID workId, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);

    Optional<Episode> findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(UUID workId, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);

    List<Episode> findAllBySourceFileIdInOrderByEpisodeNoAsc(Collection<UUID> sourceFileIds);

    List<Episode> findAllBySourceFileIdInAndStatusNotOrderByEpisodeNoAsc(
            Collection<UUID> sourceFileIds,
            org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status
    );

    boolean existsByWorkIdAndEpisodeNoAndStatusNot(UUID workId, int episodeNo, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);
}
