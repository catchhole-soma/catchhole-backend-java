package org.monitoring.catchholebackend.domain.episode.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeSourcePurgeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EpisodeSourcePurgeRequestRepository
        extends JpaRepository<EpisodeSourcePurgeRequest, UUID> {

    boolean existsByEpisodeId(UUID episodeId);

    /** 교체 정리가 대기 중이어도 명시적으로 보존한 새 원문 key는 파기 대상이 아니다. */
    @Query("select count(p)>0 from EpisodeSourcePurgeRequest p where p.episode.id=:episodeId and (p.retainedContentKey is null or p.retainedContentKey<>:contentKey)")
    boolean blocksSource(@Param("episodeId") UUID episodeId,@Param("contentKey") String contentKey);

    boolean existsByEpisodeIdIn(Collection<UUID> episodeIds);

    boolean existsByWorkIdAndPreviousEpisodeNo(UUID workId, int previousEpisodeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from EpisodeSourcePurgeRequest request where request.id = :id")
    Optional<EpisodeSourcePurgeRequest> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from EpisodeSourcePurgeRequest request
            where request.status = :status
            order by request.requestedAt asc
            """)
    List<EpisodeSourcePurgeRequest> findReadyForUpdate(
            @Param("status") EpisodeSourcePurgeStatus status,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from EpisodeSourcePurgeRequest request
            where request.status = :status
              and request.processingStartedAt < :staleBefore
            order by request.processingStartedAt asc
            """)
    List<EpisodeSourcePurgeRequest> findStaleProcessingForUpdate(
            @Param("status") EpisodeSourcePurgeStatus status,
            @Param("staleBefore") LocalDateTime staleBefore,
            Pageable pageable
    );
}
