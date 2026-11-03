package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Metadata-only reads; keep full Job loading at mutation/replay boundaries. */
public interface AnalysisJobSourceRepository extends Repository<AnalysisJob, UUID> {
    String SOURCE_VIEW = """
            select job.id as id,
                   job.work.id as workId,
                   job.jobType as jobType,
                   job.status as status,
                   job.analysisMode as analysisMode,
                   job.journalStatus as journalStatus,
                   job.reviewMode as reviewMode,
                   job.automaticAppliedAt as automaticAppliedAt,
                   job.reviewApplicationHeldAt as reviewApplicationHeldAt,
                   job.analysisRunId as analysisRunId,
                   job.runGeneration as runGeneration,
                   job.runSequence as runSequence,
                   job.predecessorJobId as predecessorJobId,
                   job.sourceKind as sourceKind,
                   job.sourceEpisodeNo as sourceEpisodeNo,
                   job.sourceContentHash as sourceContentHash,
                   job.sourceContentS3Key as sourceContentS3Key,
                   job.sourceContentS3Version as sourceContentS3Version,
                   episode.id as episodeId,
                   episode.work.id as episodeWorkId,
                   episode.episodeNo as episodeNo,
                   episode.status as episodeStatus,
                   episode.contentHash as episodeContentHash,
                   episode.contentS3Key as episodeContentS3Key,
                   episode.contentS3Version as episodeContentS3Version,
                   book.id as sourceSettingBookId,
                   book.originalFilename as sourceSettingBookName,
                   bookBatch.work.id as sourceSettingBookWorkId,
                   book.archivedAt as settingBookArchivedAt,
                   book.currentSettingBookRevision.id as currentSettingBookRevisionId,
                   revision.id as sourceSettingBookRevisionId,
                   revision.settingBook.id as revisionSettingBookId,
                   revision.revisionNo as sourceSettingBookRevisionNo,
                   revision.referenceTimeKind as sourceReferenceTimeKind,
                   revision.referenceEpisodeNo as sourceReferenceEpisodeNo,
                   revision.contentHash as revisionContentHash,
                   revision.contentS3Key as revisionContentS3Key,
                   revision.contentS3Version as revisionContentS3Version
            from AnalysisJob job
            left join job.episode episode
            left join job.sourceSettingBook book
            left join book.batch bookBatch
            left join job.sourceSettingBookRevision revision
            """;

    @Query(SOURCE_VIEW + """
            where job.id = :id
            """)
    Optional<AnalysisJobSourceView> findSourceById(@Param("id") UUID id);

    @Query(SOURCE_VIEW + """
            where job.work.id = :workId
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
              and episode.episodeNo < :sourceEpisodeNo
            order by job.createdAt desc, job.id desc
            """)
    List<AnalysisJobSourceView> findEarlierExtractionJobsForReferences(@Param("workId") UUID workId,
            @Param("sourceEpisodeNo") int sourceEpisodeNo);

    @Query(SOURCE_VIEW + """
            where job.work.id = :workId
              and job.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
            order by job.createdAt desc, job.id desc
            """)
    List<AnalysisJobSourceView> findSettingBookExtractionJobs(@Param("workId") UUID workId);

    @Query(SOURCE_VIEW + """
            where job.analysisRunId = :runId and job.runGeneration = :generation
            order by job.runSequence asc
            """)
    List<AnalysisJobSourceView> findRunMembers(@Param("runId") UUID runId, @Param("generation") Long generation);
    @Query(SOURCE_VIEW + """
            where job.work.id = :workId
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
            """)
    List<AnalysisJobSourceView> findExtractionSourcesForWork(@Param("workId") UUID workId);
}
