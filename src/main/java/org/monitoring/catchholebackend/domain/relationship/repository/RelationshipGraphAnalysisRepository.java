package org.monitoring.catchholebackend.domain.relationship.repository;

import org.monitoring.catchholebackend.domain.relationship.entity.RelationshipGraphAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RelationshipGraphAnalysisRepository
        extends JpaRepository<RelationshipGraphAnalysis, UUID> {
    List<RelationshipGraphAnalysis> findAllByWorkIdOrderByEpisodeNoAscCreatedAtAscIdAsc(
            UUID workId);

    @Query("""
            select a.id from RelationshipGraphAnalysis a
            where a.workId=:workId and a.generation=:generation and a.episodeNo=:episodeNo
            order by a.createdAt desc,a.id desc
            """)
    List<UUID> findLatestIdsForEpisode(@Param("workId") UUID workId,
            @Param("generation") long generation, @Param("episodeNo") int episodeNo, Pageable pageable);

    /** Validate copied source manifests without hydrating any frozen graph or Job JSON. */
    @Query(value = """
            select count(*) from relationship_graph_analyses a
            join episodes e on e.id=a.episode_id
            join analysis_jobs j on j.id=a.id
            where a.id in (:ids) and a.work_id=:workId and a.generation=:generation
              and a.episode_no<:cutoff and a.evidence_purged=false
              and e.status<>'ARCHIVED' and e.episode_no=a.episode_no and e.content_hash=a.source_hash
              and j.source_content_s3_key is not distinct from e.content_s3_key
              and j.source_content_s3_version is not distinct from e.content_s3_version
              and not exists (select 1 from episode_source_purge_requests p where p.episode_id=e.id)
            """, nativeQuery = true)
    long countCurrentSources(@Param("ids") Collection<UUID> ids, @Param("workId") UUID workId,
            @Param("generation") long generation, @Param("cutoff") int cutoff);
}
