package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RelationshipGraphEventRepository
        extends JpaRepository<RelationshipGraphEvent, UUID> {
    boolean existsByAnalysisId(UUID analysisId);

    List<RelationshipGraphEvent>
            findAllByWorkIdAndEpisodeNoLessThanEqualOrderByEpisodeNoAscCreatedAtAscIdAsc(
                    UUID workId, int episodeNo);

    /** GH391 정리 계획용 작품 전체 event 위치를 scalar로 한 번 읽는다(근거 jsonb를 올리지 않는다). */
    @Query("""
            select e.id as id, e.edgeId as edgeId, e.analysisId as analysisId,
                   e.episodeNo as episodeNo, e.direction as direction
            from RelationshipGraphEvent e
            where e.workId = :workId
            """)
    List<RelationshipGraphEventSlotRow> findSlotRowsByWorkId(@Param("workId") UUID workId);
}
