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

    public void assertCurrent(AnalysisJob job, Episode sourceEpisode) {
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
