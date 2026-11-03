package org.monitoring.catchholebackend.domain.relationship.repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipHoldClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationshipHoldClosureRepository extends JpaRepository<RelationshipHoldClosure, UUID> {
    @Query("select concat(cast(c.analysisId as string), ':', c.observationRef) from RelationshipHoldClosure c where c.workId = :workId")
    Set<String> findClosedKeysByWorkId(@Param("workId") UUID workId);

    @Query("""
            select new org.monitoring.catchholebackend.domain.relationship.repository.RelationshipHoldReviewRow(
                a.id, a.episodeNo, a.status, a.completionHash, a.updatedAt, a.decisions, a.bindings, j.status,
                case when e.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
                    and a.evidencePurged = false and j.sourceReferenceInvalidatedAt is null
                    and e.episodeNo = a.episodeNo and j.sourceEpisodeNo = a.episodeNo
                    and e.contentHash = a.sourceHash and j.sourceContentHash = a.sourceHash
                    and e.contentS3Key = j.sourceContentS3Key
                    and (e.contentS3Version = j.sourceContentS3Version
                        or e.contentS3Version is null and j.sourceContentS3Version is null)
                    and not exists (select p.id from EpisodeSourcePurgeRequest p where p.episode.id = e.id)
                    then true else false end)
            from RelationshipGraphAnalysis a join AnalysisJob j on j.id = a.id
                join Episode e on e.id = a.episodeId
            where a.workId = :workId and a.generation = :generation and a.episodeNo < :beforeEpisodeNo
            order by a.episodeNo asc, a.createdAt asc, a.id asc
            """)
    List<RelationshipHoldReviewRow> findReviewRows(@Param("workId") UUID workId,
            @Param("generation") long generation, @Param("beforeEpisodeNo") int beforeEpisodeNo);
}
