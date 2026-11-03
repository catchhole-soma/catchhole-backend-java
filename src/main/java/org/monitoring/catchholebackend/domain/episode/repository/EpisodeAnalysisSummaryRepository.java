package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface EpisodeAnalysisSummaryRepository extends Repository<AnalysisJob, UUID> {
    @Query("""
            select new org.monitoring.catchholebackend.domain.episode.repository.EpisodeAnalysisSummary(
                job.id, job.batch.id, job.episode.id, job.status, job.analysisMode,
                job.reviewMode, job.journalStatus, job.automaticAppliedAt,
                job.reviewApplicationHeldAt, job.summaryJson)
            from AnalysisJob job
            where job.work.id = :workId
              and job.batch.id in :batchIds
              and job.episode.id in :episodeIds
              and job.jobType in (
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION,
                  org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.EPISODE_VALIDATION)
              and not exists (
                  select newer.id from AnalysisJob newer
                  where newer.work.id = job.work.id
                    and newer.batch.id = job.batch.id
                    and newer.episode.id = job.episode.id
                    and newer.jobType in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.EPISODE_VALIDATION)
                    and newer.createdAt > job.createdAt)
            order by job.createdAt desc
            """)
    List<EpisodeAnalysisSummary> findLatestForEpisodes(
            @Param("workId") UUID workId,
            @Param("batchIds") Collection<UUID> batchIds,
            @Param("episodeIds") Collection<UUID> episodeIds
    );
}
