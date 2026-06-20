package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {

    Optional<Episode> findByIdAndWorkId(UUID id, UUID workId);

    List<Episode> findAllByWorkIdOrderByEpisodeNoDesc(UUID workId);

    Optional<Episode> findFirstByWorkIdOrderByEpisodeNoDesc(UUID workId);

    List<Episode> findAllBySourceFileIdInOrderByEpisodeNoAsc(Collection<UUID> sourceFileIds);

    boolean existsByWorkIdAndEpisodeNo(UUID workId, int episodeNo);
}
