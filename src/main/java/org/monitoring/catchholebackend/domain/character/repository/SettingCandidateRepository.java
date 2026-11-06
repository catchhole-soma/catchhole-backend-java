package org.monitoring.catchholebackend.domain.character.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateMatchStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus;
import org.monitoring.catchholebackend.domain.character.type.SettingEntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettingCandidateRepository extends JpaRepository<SettingCandidate, UUID> {

    @Query("""
            select distinct candidate.analysisJob from SettingCandidate candidate
            where candidate.work.id = :workId and candidate.id in :candidateIds
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW
            """)
    List<org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob> findPendingReviewSourceJobs(
            @Param("workId") UUID workId, @Param("candidateIds") Collection<UUID> candidateIds);

    boolean existsByAnalysisJobIdAndComparisonStatusAndComparisonFailureCodeIn(UUID analysisJobId,
            CharacterFactComparisonStatus comparisonStatus,
            Collection<org.monitoring.catchholebackend.domain.analysis.type.AnalysisFailureCode> failureCodes);

    @Query("""
            select candidate from SettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.candidateKind = org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind.CHARACTER_DISCOVERY
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED
              and candidate.matchedCharacterId is not null
            order by candidate.createdAt, candidate.id
            """)
    List<SettingCandidate> findConfirmedDiscoveries(@Param("workId") UUID workId);

    /**
     * 관계도 노드 해소에 쓰는 작품 전체 확정 이름 목록(대표 이름·별칭)을 scalar로 한 번 읽는다. 관측 이름과 포함 규칙으로
     * 비교하므로 관측 이름으로 거르지 않는다(GH385). 후보·분석 Job 엔티티와 JSON 열을 올리지 않는다.
     * 원문 회차가 cutoff 이하인 확정 후보만 읽으므로 미래 회차에 확정된 별칭은 과거 문맥에 들어오지 않는다.
     */
    @Query("""
            select distinct candidate.entityName as entityName,
                   candidate.matchedCharacterId as characterId
            from SettingCandidate candidate
            join candidate.episode episode
            where candidate.work.id = :workId
              and candidate.candidateKind in :kinds
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED
              and candidate.matchedCharacterId is not null
              and episode.episodeNo <= :cutoff
              and episode.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
              and exists (select character.id from WorkCharacter character
                          where character.id = candidate.matchedCharacterId and character.work.id = :workId)
            """)
    List<ConfirmedCharacterIdentityRow> findConfirmedIdentities(
            @Param("workId") UUID workId,
            @Param("kinds") Collection<org.monitoring.catchholebackend.domain.character.type.SettingCandidateKind> kinds,
            @Param("cutoff") int cutoff);

    /**
     * 관계 백필의 과거 이름 조회. 후보를 만든 Job의 원문(회차·hash·key·version)이 현재 회차와 같고 파기 요청이 없는
     * 확정 후보만 scalar로 읽는다. Job은 join 조건에만 쓰고 엔티티로 올리지 않는다.
     */
    @Query("""
            select distinct candidate.entityName as entityName,
                   candidate.matchedCharacterId as characterId
            from SettingCandidate candidate
            join candidate.episode episode
            join candidate.analysisJob job
            where candidate.work.id = :workId and job.work.id = :workId
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED
              and candidate.matchedCharacterId is not null
              and episode.episodeNo <= :cutoff
              and episode.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
              and job.episode.id = episode.id
              and job.sourceEpisodeNo = episode.episodeNo
              and ((job.sourceContentHash is null and episode.contentHash is null)
                    or job.sourceContentHash = episode.contentHash)
              and ((job.sourceContentS3Key is null and episode.contentS3Key is null)
                    or job.sourceContentS3Key = episode.contentS3Key)
              and ((job.sourceContentS3Version is null and episode.contentS3Version is null)
                    or job.sourceContentS3Version = episode.contentS3Version)
              and not exists (select purge.id from EpisodeSourcePurgeRequest purge where purge.episode.id = episode.id)
              and exists (select character.id from WorkCharacter character
                          where character.id = candidate.matchedCharacterId and character.work.id = :workId)
            """)
    List<ConfirmedCharacterIdentityRow> findVerifiedConfirmedIdentities(
            @Param("workId") UUID workId, @Param("cutoff") int cutoff);

    @Query("""
            select min(coalesce(episode.episodeNo, 0)) from SettingCandidate candidate
            left join candidate.episode episode
            where candidate.work.id = :workId and candidate.id in :candidateIds
              and candidate.reviewStatus = :reviewStatus
            """)
    Integer findMutationSourceEpisodeNo(
            @Param("workId") UUID workId,
            @Param("candidateIds") java.util.Collection<UUID> candidateIds,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select distinct candidate.matchedCharacterId from SettingCandidate candidate
            left join candidate.characterComparisonBatch comparisonBatch
            where candidate.work.id = :workId and candidate.reviewStatus = :reviewStatus
              and candidate.matchedCharacterId is not null
              and (comparisonBatch.provisionalSubjectKey = :subjectKey or candidate.id = :anchorId)
            """)
    List<UUID> findPromotedProvisionalCharacterIds(
            @Param("workId") UUID workId, @Param("subjectKey") String subjectKey,
            @Param("anchorId") UUID anchorId, @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus);

    List<SettingCandidate> findAllByAnalysisJobIdAndProvisionalSubjectKeyIsNotNull(UUID analysisJobId);

    List<SettingCandidate> findAllByAnalysisJobIdOrderByCreatedAtAscIdAsc(UUID analysisJobId);

    @Query("""
            select candidate from SettingCandidate candidate
            where candidate.analysisJob.id in :analysisJobIds
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findPendingReferencesByAnalysisJobIds(@Param("analysisJobIds") Collection<UUID> analysisJobIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.provisionalSubjectKey = :subjectKey
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findProvisionalComparisonGroupForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("subjectKey") String subjectKey,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @Query("""
            select distinct candidate
            from SettingCandidate candidate
            left join candidate.episode episode
            left join candidate.analysisJob analysisJob
            left join analysisJob.episode jobEpisode
            left join analysisJob.targetEpisodes jobTargetEpisode
            where episode.id = :episodeId
               or (episode is null and (
                    jobEpisode.id = :episodeId
                    or jobTargetEpisode.id = :episodeId
               ))
            """)
    List<SettingCandidate> findAllByAnalysisTargetEpisodeId(@Param("episodeId") UUID episodeId);

    List<SettingCandidate> findAllByWorkIdOrderByCreatedAtDesc(UUID workId);

    List<SettingCandidate> findAllByWorkIdAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            SettingCandidateReviewStatus reviewStatus
    );

    List<SettingCandidate> findAllByWorkIdAndEntityNameOrderByCreatedAtDesc(
            UUID workId,
            String entityName
    );

    List<SettingCandidate> findAllByWorkIdAndEntityNameAndReviewStatusOrderByCreatedAtDesc(
            UUID workId,
            String entityName,
            SettingCandidateReviewStatus reviewStatus
    );

    @EntityGraph(attributePaths = {"work", "episode", "matchedCharacter", "characterComparisonBatch", "characterComparisonBatch.matchedCharacter"})
    @Query(
            value = """
                    select candidate
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    left join candidate.episode episode
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
                      and ((:reviewStatus is null and candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED)
                   or candidate.reviewStatus = :reviewStatus)
                      and candidate.matchStatus in :matchStatuses
                    order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
                    """,
            countQuery = """
                    select count(candidate)
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
                      and ((:reviewStatus is null and candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED)
                   or candidate.reviewStatus = :reviewStatus)
                      and candidate.matchStatus in :matchStatuses
                    """
    )
    Page<SettingCandidate> findReviewListPage(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatuses") Collection<SettingCandidateMatchStatus> matchStatuses,
            Pageable pageable,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default Page<SettingCandidate> findReviewListPage(UUID workId, UUID batchId, SettingCandidateReviewStatus reviewStatus, Collection<SettingCandidateMatchStatus> matchStatuses, Pageable pageable) {
        return findReviewListPage(workId, batchId, reviewStatus, matchStatuses, pageable, null);
    }

    @EntityGraph(attributePaths = {"work", "episode", "matchedCharacter", "characterComparisonBatch", "characterComparisonBatch.matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and ((:reviewStatus is null and candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED)
                   or candidate.reviewStatus = :reviewStatus)
              and candidate.matchStatus in :matchStatuses
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findReviewListCandidates(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatuses") Collection<SettingCandidateMatchStatus> matchStatuses,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<SettingCandidate> findReviewListCandidates(UUID workId, UUID batchId, SettingCandidateReviewStatus reviewStatus, Collection<SettingCandidateMatchStatus> matchStatuses) {
        return findReviewListCandidates(workId, batchId, reviewStatus, matchStatuses, null);
    }

    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query(
            value = """
                    select candidate
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    left join candidate.episode episode
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
                      and ((:reviewStatus is null and candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED)
                   or candidate.reviewStatus = :reviewStatus)
                      and candidate.matchStatus in :matchStatuses
                    order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
                    """,
            countQuery = """
                    select count(candidate)
                    from SettingCandidate candidate
                    join candidate.analysisJob analysisJob
                    where candidate.work.id = :workId
                      and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
                      and ((:reviewStatus is null and candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED)
                   or candidate.reviewStatus = :reviewStatus)
                      and candidate.matchStatus in :matchStatuses
                    """
    )
    Page<SettingCandidate> findReviewPage(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatuses") Collection<SettingCandidateMatchStatus> matchStatuses,
            Pageable pageable,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default Page<SettingCandidate> findReviewPage(UUID workId, UUID batchId, SettingCandidateReviewStatus reviewStatus, Collection<SettingCandidateMatchStatus> matchStatuses, Pageable pageable) {
        return findReviewPage(workId, batchId, reviewStatus, matchStatuses, pageable, null);
    }

    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and ((:reviewStatus is null and candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED)
                   or candidate.reviewStatus = :reviewStatus)
              and candidate.matchStatus in :matchStatuses
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findReviewCandidates(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatuses") Collection<SettingCandidateMatchStatus> matchStatuses,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<SettingCandidate> findReviewCandidates(UUID workId, UUID batchId, SettingCandidateReviewStatus reviewStatus, Collection<SettingCandidateMatchStatus> matchStatuses) {
        return findReviewCandidates(workId, batchId, reviewStatus, matchStatuses, null);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "analysisJob.batch", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and candidate.reviewStatus = :reviewStatus
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findAllPendingInBatchForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<SettingCandidate> findAllPendingInBatchForUpdate(UUID workId, UUID batchId, SettingCandidateReviewStatus reviewStatus) {
        return findAllPendingInBatchForUpdate(workId, batchId, reviewStatus, null);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "episode", "analysisJob", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and candidate.id in :candidateIds
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findAllByIdsAndBatchForUpdate(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("candidateIds") Collection<UUID> candidateIds,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<SettingCandidate> findAllByIdsAndBatchForUpdate(UUID workId, UUID batchId, Collection<UUID> candidateIds) {
        return findAllByIdsAndBatchForUpdate(workId, batchId, candidateIds, null);
    }

    @Query("""
            select analysisJob.id as analysisJobId,
                   count(candidate) as totalCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW then 1 else 0 end), 0) as reviewedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW then 1 else 0 end), 0) as pendingCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW
                     and candidate.comparisonStatus not in (org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PROCESSING)
                     and not (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING))
                     then 1 else 0 end), 0) as directReviewCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW
                     and (candidate.comparisonStatus in (org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PROCESSING)
                         or (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)))
                     then 1 else 0 end), 0) as processingCandidateCount
            from SettingCandidate candidate join candidate.analysisJob analysisJob
            where candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED
              and candidate.work.id = :workId and analysisJob.work.id = :workId
              and analysisJob.id in :analysisJobIds
              and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.SETTING_BOOK
            group by analysisJob.id
            """)
    List<org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobCandidateReviewCounts> countReviewSummaryByAnalysisJobIds(
            @Param("workId") UUID workId, @Param("analysisJobIds") List<UUID> analysisJobIds);

    @Query("""
            select count(candidate) as totalCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CONFIRMED then 1 else 0 end), 0) as confirmedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.DISMISSED then 1 else 0 end), 0) as dismissedCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingStatus
                     and candidate.comparisonStatus not in (org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PROCESSING)
                     and not (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING))
                     then 1 else 0 end), 0) as directReviewCandidateCount,
                   coalesce(sum(case when candidate.reviewStatus = :pendingStatus
                     and (candidate.comparisonStatus in (org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PENDING, org.monitoring.catchholebackend.domain.character.type.CharacterFactComparisonStatus.PROCESSING)
                         or (analysisJob.reviewMode = org.monitoring.catchholebackend.domain.analysis.type.AnalysisReviewMode.AUTOMATIC
                         and analysisJob.automaticAppliedAt is null
                         and analysisJob.status in (org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING, org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING)))
                     then 1 else 0 end), 0) as processingCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus <> :pendingStatus then 1
                       else 0
                   end), 0) as reviewedCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus then 1
                       else 0
                   end), 0) as pendingCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus
                        and candidate.matchStatus = :matchRequiredStatus then 1
                       else 0
                   end), 0) as matchRequiredCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus
                        and (candidate.matchStatus = :matchRequiredStatus
                             or candidate.comparisonStatus in :attentionComparisonStatuses) then 1
                       else 0
                   end), 0) as attentionRequiredCandidateCount
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED
              and candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
            """)
    SettingCandidateBatchCounts countReviewSummary(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("pendingStatus") SettingCandidateReviewStatus pendingStatus,
            @Param("matchRequiredStatus") SettingCandidateMatchStatus matchRequiredStatus,
            @Param("attentionComparisonStatuses") Collection<CharacterFactComparisonStatus> attentionComparisonStatuses,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default SettingCandidateBatchCounts countReviewSummary(UUID workId, UUID batchId, SettingCandidateReviewStatus pendingStatus, SettingCandidateMatchStatus matchRequiredStatus, Collection<CharacterFactComparisonStatus> attentionComparisonStatuses) {
        return countReviewSummary(workId, batchId, pendingStatus, matchRequiredStatus, attentionComparisonStatuses, null);
    }

    @Query("""
            select analysisJob.batch.id as batchId,
                   count(candidate) as totalCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus <> :pendingStatus then 1
                       else 0
                   end), 0) as reviewedCandidateCount,
                   coalesce(sum(case
                       when candidate.reviewStatus = :pendingStatus then 1
                       else 0
                   end), 0) as pendingCandidateCount
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            where candidate.reviewStatus <> org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.CLOSED
              and candidate.work.id = :workId
              and analysisJob.batch.id in :batchIds
              and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE
            group by analysisJob.batch.id
            """)
    List<SettingCandidateBatchReviewCounts> countReviewSummaryByBatchIds(
            @Param("workId") UUID workId,
            @Param("batchIds") List<UUID> batchIds,
            @Param("pendingStatus") SettingCandidateReviewStatus pendingStatus
    );

    /**
     * 새 분석 작업이 대체할 회차의 미검토 후보만 제거한다.
     * 호출자는 새 Job 저장 전에 실행해 현재 Job의 후보가 함께 지워지지 않도록 해야 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from SettingCandidate candidate
            where candidate.work.id = :workId
              and (
                  candidate.episode.id in :episodeIds
                  or candidate.analysisJob.episode.id in :episodeIds
              )
              and candidate.reviewStatus = :reviewStatus
              and candidate.analysisJob.id in (
                  select analysisJob.id
                  from AnalysisJob analysisJob
                  where analysisJob.work.id = :workId
                    and analysisJob.batch.id = :batchId
                    and analysisJob.jobType = :jobType
              )
            """)
    int deleteAllByAnalysisTargetAndReviewStatus(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("episodeIds") Collection<UUID> episodeIds,
            @Param("jobType") AnalysisJobType jobType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.work.id = :workId
              and (
                  candidate.episode.id in :episodeIds
                  or candidate.analysisJob.episode.id in :episodeIds
              )
              and candidate.reviewStatus = :reviewStatus
              and candidate.analysisJob.batch.id = :batchId
              and candidate.analysisJob.jobType = :jobType
            """)
    List<SettingCandidate> findAllSupersededPendingCandidates(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("episodeIds") Collection<UUID> episodeIds,
            @Param("jobType") AnalysisJobType jobType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.work.id = :workId
              and candidate.entityType = :entityType
              and candidate.reviewStatus = :reviewStatus
              and candidate.matchStatus = :matchStatus
              and lower(function('regexp_replace', trim(candidate.entityName), '\\s+', ' ', 'g')) = :groupKey
            order by candidate.createdAt desc
            """)
    List<SettingCandidate> findAllByNormalizedEntityNameAndMatchState(
            @Param("workId") UUID workId,
            @Param("groupKey") String groupKey,
            @Param("entityType") SettingEntityType entityType,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("matchStatus") SettingCandidateMatchStatus matchStatus
    );

    Optional<SettingCandidate> findByIdAndWorkId(UUID id, UUID workId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidate from SettingCandidate candidate where candidate.id = :id and candidate.work.id = :workId")
    Optional<SettingCandidate> findByIdAndWorkIdForUpdate(
            @Param("id") UUID id,
            @Param("workId") UUID workId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select candidate
            from SettingCandidate candidate
            join fetch candidate.work
            left join fetch candidate.matchedCharacter
            where candidate.analysisJob.id = :analysisJobId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findComparisonClaimCandidates(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // 같은 Job의 후보 묶음에서는 Job 상태 JSON을 행마다 fetch하지 않는다.
    @EntityGraph(attributePaths = {"work", "episode", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.matchedCharacterId = :characterId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findComparisonGroupCandidatesForUpdate(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("characterId") UUID characterId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"work", "episode", "matchedCharacter", "characterComparisonBatch"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.characterComparisonBatch.id = :comparisonBatchId
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findAllByCharacterComparisonBatchIdForUpdate(
            @Param("comparisonBatchId") UUID comparisonBatchId
    );

    @EntityGraph(attributePaths = {"work", "episode", "matchedCharacter"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.matchedCharacterId = :characterId
              and candidate.reviewStatus = :reviewStatus
              and candidate.comparisonStatus = :comparisonStatus
              and candidate.characterComparisonBatch is not null
            order by candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findCompletedComparisonCandidates(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("characterId") UUID characterId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @EntityGraph(attributePaths = {"episode", "analysisJob", "analysisJob.batch"})
    @Query("""
            select candidate
            from SettingCandidate candidate
            join candidate.analysisJob analysisJob
            left join candidate.episode episode
            where candidate.work.id = :workId
              and analysisJob.batch.id = :batchId
              and ((:sourceAnalysisJobId is null and analysisJob.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE)
                or analysisJob.id = :sourceAnalysisJobId)
              and candidate.matchedCharacterId = :characterId
              and candidate.reviewStatus = :reviewStatus
              and candidate.attributeName is not null
            order by episode.episodeNo asc, candidate.createdAt asc, candidate.id asc
            """)
    List<SettingCandidate> findPendingComparisonChronology(
            @Param("workId") UUID workId,
            @Param("batchId") UUID batchId,
            @Param("characterId") UUID characterId,
            @Param("reviewStatus") SettingCandidateReviewStatus reviewStatus,
            @Param("sourceAnalysisJobId") UUID sourceAnalysisJobId
    );

    default List<SettingCandidate> findPendingComparisonChronology(UUID workId, UUID batchId, UUID characterId, SettingCandidateReviewStatus reviewStatus) {
        return findPendingComparisonChronology(workId, batchId, characterId, reviewStatus, null);
    }

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.comparisonStatus = :comparisonStatus
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            """)
    List<SettingCandidate> findAllByAnalysisJobIdAndComparisonStatus(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("comparisonStatus") CharacterFactComparisonStatus comparisonStatus
    );

    @Query("""
            select candidate
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.comparisonStatus in :comparisonStatuses
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            """)
    List<SettingCandidate> findAllByAnalysisJobIdAndComparisonStatusIn(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("comparisonStatuses") Collection<CharacterFactComparisonStatus> comparisonStatuses
    );

    @Query("""
            select case when count(candidate) > 0 then true else false end
            from SettingCandidate candidate
            where candidate.analysisJob.id = :analysisJobId
              and candidate.comparisonStatus in :comparisonStatuses
              and not exists (
                  select hiddenJob.id
                  from AnalysisJob hiddenJob
                  where hiddenJob.settingCandidate = candidate
                    and hiddenJob.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.CHARACTER_FACT_COMPARISON
                    and hiddenJob.status in (
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.PENDING,
                        org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus.RUNNING
                    )
              )
            """)
    boolean existsByAnalysisJobIdAndComparisonStatusIn(
            @Param("analysisJobId") UUID analysisJobId,
            @Param("comparisonStatuses") Collection<CharacterFactComparisonStatus> comparisonStatuses
    );

    Optional<SettingCandidate> findByIdAndWorkIdAndAnalysisJobBatchId(UUID id, UUID workId, UUID batchId);

    /** Scalar-only preflight: never select AnalysisJob's input/journal JSON. */
    @Query("""
            select candidate.id as id, job.id as sourceJobId, candidate.updatedAt as updatedAt,
                   job.updatedAt as sourceUpdatedAt, cast(job.reviewMode as string) as reviewMode,
                   cast(job.status as string) as jobStatus, cast(job.analysisMode as string) as analysisMode,
                   cast(job.journalStatus as string) as journalStatus,
                   job.automaticAppliedAt as automaticAppliedAt, job.reviewApplicationHeldAt as reviewApplicationHeldAt,
                   cast(candidate.comparisonStatus as string) as comparisonStatus,
                   cast(candidate.suggestedOperation as string) as suggestedOperation,
                   cast(candidate.automaticReviewHoldReason as string) as holdReason,
                   candidate.userModified as userModified, cast(candidate.reviewedApplicationMode as string) as reviewedApplicationMode,
                   cast(candidate.matchStatus as string) as matchStatus, cast(candidate.candidateKind as string) as candidateKind,
                   cast(null as string) as comparisonReviewReason, cast(null as string) as consolidationStatus
            from SettingCandidate candidate
            join candidate.analysisJob job
            join candidate.episode episode
            where candidate.work.id = :workId and job.work.id = :workId
              and candidate.reviewStatus = org.monitoring.catchholebackend.domain.character.type.SettingCandidateReviewStatus.PENDING_REVIEW
              and job.sourceKind = org.monitoring.catchholebackend.domain.analysis.type.AnalysisSourceKind.EPISODE
              and job.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
              and job.episode.id = episode.id and episode.episodeNo > 0 and episode.episodeNo < :beforeEpisodeNo
              and episode.status <> org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus.ARCHIVED
              and job.sourceReferenceInvalidatedAt is null
              and job.sourceEpisodeNo = episode.episodeNo
              and job.sourceContentS3Key = episode.contentS3Key and job.sourceContentHash = episode.contentHash
              and ((job.sourceContentS3Version is null and episode.contentS3Version is null)
                    or job.sourceContentS3Version = episode.contentS3Version)
              and not exists (select newer.id from AnalysisJob newer
                  where newer.episode.id = episode.id
                    and newer.jobType = org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION
                    and (newer.createdAt > job.createdAt or (newer.createdAt = job.createdAt and newer.id > job.id)))
            order by candidate.id
            """)
    List<org.monitoring.catchholebackend.domain.analysis.repository.AnalysisCandidateGateRow> findContinuationGateRows(
            @Param("workId") UUID workId, @Param("beforeEpisodeNo") int beforeEpisodeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidate from SettingCandidate candidate where candidate.work.id = :workId and candidate.id in :ids order by candidate.id")
    List<SettingCandidate> findContinuationCandidatesForUpdate(@Param("workId") UUID workId, @Param("ids") Collection<UUID> ids);
}
