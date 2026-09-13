package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface EpisodeUploadPolicyRepository extends Repository<Episode, UUID> {

    // 재시도 수가 아닌 현재 원문으로 분석된 회차를 센다. 원문 snapshot이 없는 과거 Job은
    // 같은 원본 batch에서 원문 변경 이후 시작한 성공만 인정한다.
    @Query(value = """
            select count(*)
            from episodes episode
            join upload_files source_file on source_file.id = episode.source_file_id
            join upload_batches batch on batch.id = source_file.batch_id
            where episode.work_id = :workId
              and episode.status <> 'ARCHIVED'
              and source_file.archived_at is null
              and source_file.file_role = 'EPISODE'
              and batch.upload_type = 'SINGLE_EPISODE'
              and batch.status = 'COMPLETED'
              and exists (
                  select 1 from analysis_jobs job
                  join analysis_job_episode_targets target on target.analysis_job_id = job.id
                  where target.episode_id = episode.id
                    and job.work_id = episode.work_id
                    and job.batch_id = batch.id
                    and job.job_type = 'SETTING_EXTRACTION'
                    and job.status = 'SUCCEEDED'
                    and (
                        (job.source_content_hash is not null
                         and job.source_content_hash = episode.content_hash
                         and job.source_content_s3_key = episode.content_s3_key
                         and (job.source_content_s3_version = episode.content_s3_version
                              or (job.source_content_s3_version is null and episode.content_s3_version is null))
                         and job.source_episode_no = episode.episode_no)
                        or (job.source_content_hash is null
                            and job.source_content_s3_key is null
                            and job.source_content_s3_version is null
                            and job.source_episode_no is null
                            and job.started_at >= episode.content_updated_at)
                    )
              )
            """, nativeQuery = true)
    long countCompletedSingleEpisodes(@Param("workId") UUID workId);

    @Query(value = """
            select count(*) from setting_candidates candidate
            left join episodes episode on episode.id = candidate.episode_id
            where candidate.work_id = :workId
              and candidate.review_status = 'PENDING_REVIEW'
              and (episode.id is null or episode.status <> 'ARCHIVED')
            """, nativeQuery = true)
    long countPendingCharacterCandidates(@Param("workId") UUID workId);

    @Query(value = """
            select count(*) from world_setting_candidates candidate
            join episodes episode on episode.id = candidate.source_episode_id
            where candidate.work_id = :workId
              and candidate.review_status = 'PENDING_REVIEW'
              and episode.status <> 'ARCHIVED'
            """, nativeQuery = true)
    long countPendingWorldSettingCandidates(@Param("workId") UUID workId);
}
