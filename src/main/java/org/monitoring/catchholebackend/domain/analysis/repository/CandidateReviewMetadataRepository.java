package org.monitoring.catchholebackend.domain.analysis.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** ID는 소유권 검증된 후보 또는 읽기 출처 검증에서만 받는다. */
public interface CandidateReviewMetadataRepository extends Repository<AnalysisJob, UUID> {
    default List<CandidateReviewJobMetadata> findByIds(Collection<UUID> jobIds) {
        return jobIds.isEmpty() ? List.of() : findMetadataByIds(jobIds);
    }

    @Query("""
            select new org.monitoring.catchholebackend.domain.analysis.repository.CandidateReviewJobMetadata(
                job.id, job.work.id, job.batch.id, job.jobType, job.analysisMode, job.reviewMode, job.status,
                job.journalStatus, job.automaticAppliedAt, job.sourceKind, episode.id, job.sourceEpisodeNo,
                episode.episodeNo, book.id, book.originalFilename, revision.id, revision.revisionNo,
                job.sourceContentHash, revision.referenceTimeKind, revision.referenceEpisodeNo, job.characterComparisonInputHash,
                case when job.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK then
                    case when book.id is not null and revision.id is not null and book.archivedAt is null
                        and book.currentSettingBookRevision.id = revision.id
                        and job.sourceContentHash is not distinct from revision.contentHash
                        and job.sourceContentS3Key is not distinct from revision.contentS3Key
                        and job.sourceContentS3Version is not distinct from revision.contentS3Version then true else false end
                else case when job.analysisMode <> org.monitoring.catchholebackend.domain.analysis.type.AnalysisMode.ORDERED_PROVISIONAL
                    or (episode.id is not null and job.sourceEpisodeNo is not distinct from episode.episodeNo
                        and episode.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
                        and job.sourceContentHash is not distinct from episode.contentHash
                        and job.sourceContentS3Key is not distinct from episode.contentS3Key
                        and job.sourceContentS3Version is not distinct from episode.contentS3Version) then true else false end end,
                case when not exists (select later.id from AnalysisJob later
                    where later.sourceSettingBook.id = book.id
                      and later.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                      and (later.createdAt > job.createdAt or (later.createdAt = job.createdAt and later.id > job.id))) then true else false end)
            from AnalysisJob job
            left join job.episode episode
            left join job.sourceSettingBook book
            left join job.sourceSettingBookRevision revision
            where job.id in :jobIds
            """)
    List<CandidateReviewJobMetadata> findMetadataByIds(@Param("jobIds") Collection<UUID> jobIds);
}
