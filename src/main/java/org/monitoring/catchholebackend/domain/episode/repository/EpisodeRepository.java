package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {

    // 안내 자격은 세 번째 유효 회차의 존재만 필요하므로 집계 입력을 3행으로 제한한다.
    @Query(value = """
            SELECT COUNT(*) = 3 FROM (
                SELECT 1
                FROM works w
                JOIN episodes e ON e.work_id = w.id
                WHERE w.member_id = :memberId
                  AND w.lifecycle_status = 'ACTIVE'
                  AND e.status <> 'ARCHIVED'
                LIMIT 3
            ) eligible_episodes
            """, nativeQuery = true)
    boolean existsAtLeastThreePromptEligibleEpisodes(@Param("memberId") Long memberId);

    Optional<Episode> findByIdAndWorkId(UUID id, UUID workId);

    Optional<Episode> findByIdAndWorkIdAndStatusNot(UUID id, UUID workId, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);

    Optional<Episode> findByWorkIdAndEpisodeNo(UUID workId, int episodeNo);

    Optional<Episode> findByWorkIdAndEpisodeNoAndStatusNot(
            UUID workId,
            int episodeNo,
            org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status
    );

    List<Episode> findAllByWorkIdAndIdIn(UUID workId, Collection<UUID> ids);

    List<Episode> findAllByWorkIdAndStatusNotOrderByEpisodeNoDesc(UUID workId, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);

    Optional<Episode> findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(UUID workId, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);

    List<Episode> findAllBySourceFileIdInOrderByEpisodeNoAsc(Collection<UUID> sourceFileIds);

    List<Episode> findAllBySourceFileIdInAndStatusNotOrderByEpisodeNoAsc(
            Collection<UUID> sourceFileIds,
            org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status
    );

    boolean existsByWorkIdAndEpisodeNoAndStatusNot(UUID workId, int episodeNo, org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus status);
}
