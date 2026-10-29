package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** 사람의 확정은 후보를 만든 원문이 현재도 유효할 때만 허용한다. */
@Component
@RequiredArgsConstructor
public class AnalysisCandidateSourceGuard {
    private final EpisodeSourcePurgeRequestRepository purgeRequests;
    private final org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository jobs;

    public void assertCurrent(AnalysisJob job, Episode sourceEpisode) {
        var targets = new java.util.HashSet<java.util.UUID>();
        if (sourceEpisode != null) targets.add(sourceEpisode.getId());
        if (job != null) {
            job.getTargetEpisodes().forEach(e -> targets.add(e.getId()));
            if (job.getEpisode() != null) targets.add(job.getEpisode().getId());
        }
        // GH312: 현재 검수 report에 검토 종료 receipt가 있으면 후보 쓰기를 허용한다. release 회차의 수동 확정 제한은 S3 범위다.
        if (!targets.isEmpty() && jobs.existsValidationStagingForEpisodes(targets)) {
            throw new AppException(org.monitoring.catchholebackend.domain.validation.exception.ValidationErrorCode.VALIDATION_ERROR_REVIEW_PENDING);
        }
        if (job != null && job.isSettingBookSource()) {
            if (sourceEpisode != null || !job.hasCurrentSourceVersion()) {
                throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
            }
            var latest=jobs.findFirstBySourceSettingBookIdAndJobTypeOrderByCreatedAtDescIdDesc(job.getSourceSettingBook().getId(),
                    org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType.SETTING_EXTRACTION).orElseThrow(
                    () -> new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT));
            if (!latest.getId().equals(job.getId())) throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_SUPERSEDED);
            return;
        }
        Episode episode = sourceEpisode != null ? sourceEpisode : job == null ? null : job.getEpisode();
        if (episode == null) return;
        if (episode.getStatus() == EpisodeStatus.ARCHIVED
                || episode.getId() != null && purgeRequests.existsByEpisodeId(episode.getId())) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        }
        // 생성 시 원문 manifest가 없던 legacy Job은 보관·파기만 검사한다. 신규 MANUAL도 manifest를 가진다.
        if (job != null && job.getSourceEpisodeNo() != null
                && (!Objects.equals(job.getSourceEpisodeNo(), episode.getEpisodeNo())
                || !Objects.equals(job.getSourceContentHash(), episode.getContentHash())
                || !Objects.equals(job.getSourceContentS3Key(), episode.getContentS3Key())
                || !Objects.equals(job.getSourceContentS3Version(), episode.getContentS3Version()))) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
        }
    }
}
