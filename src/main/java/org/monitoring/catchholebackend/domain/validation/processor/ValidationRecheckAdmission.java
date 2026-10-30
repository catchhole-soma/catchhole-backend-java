package org.monitoring.catchholebackend.domain.validation.processor;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeStatus;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.springframework.stereotype.Component;

/**
 * 재검수(POST /{reportId}/rechecks)의 회차 조건(#314 B2). {@code ValidationRecheckServiceImpl}이 요청을 거절할 때와
 * {@code ValidationReviewGate}가 회차 전체 확정(APPLIED) report의 allowedActions에 RECHECK를 넣을 때 같은 판정을 쓴다.
 * 요청마다 달라지는 값(reportVersion·원고 hash/key/version/revision·기준 식별값·멱등 키)은 재검수 요청만 검사한다. 상태를 바꾸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ValidationRecheckAdmission {
    private final EpisodeRepository episodes;
    private final EpisodeSourcePurgeRequestRepository purges;
    private final EntityManager em;
    private final ValidationExecutionAccess access;

    /** 대상 회차가 작품의 최신 활성 회차이고 원문 파기 대기가 없다. 보관된 회차는 호출 전에 거른다. */
    public boolean latestAvailableTarget(UUID workId, Episode target) {
        return episodes.findFirstByWorkIdAndStatusNotOrderByEpisodeNoDesc(workId, EpisodeStatus.ARCHIVED).map(e -> e.getId().equals(target.getId())).orElse(false)
                && !purges.blocksSource(target.getId(), target.getContentS3Key());
    }

    /** 회차(직접 또는 target membership)의 PENDING/RUNNING 추출·비교 Job. 진행 중 실행을 끊지 않도록 재검수를 거절한다. */
    public boolean analysisRunning(UUID workId, UUID episodeId) {
        return em.createQuery("select count(j) from AnalysisJob j where j.work.id=:work and j.status in ('PENDING','RUNNING') and (j.episode.id=:episode or exists (select t.id from AnalysisJob owner join owner.targetEpisodes t where owner.id=j.id and t.id=:episode))", Long.class)
                .setParameter("work", workId).setParameter("episode", episodeId).getSingleResult() != 0;
    }

    /** 회차의 현재 원본 업로드 batch(같은 작품). 새 report를 이 batch에 묶는다. */
    public java.util.Optional<org.monitoring.catchholebackend.domain.upload.entity.UploadBatch> currentBatch(UUID workId, UUID episodeId) {
        return access.currentUploadBatch(episodeId).filter(batch -> batch.getWork().getId().equals(workId));
    }

    /** 위 회차 조건을 모두 만족하면 서버가 같은 원고의 진단 재검수를 받는다(APPLIED report는 VALIDATION_RECHECK_INPUT_UNCHANGED 대상이 아니다). */
    public boolean admitsEpisode(ValidationReport report) {
        UUID workId = report.getWork().getId();
        var target = episodes.findByIdAndWorkIdAndStatusNot(report.getEpisode().getId(), workId, EpisodeStatus.ARCHIVED).orElse(null);
        return target != null && latestAvailableTarget(workId, target) && !analysisRunning(workId, target.getId()) && currentBatch(workId, target.getId()).isPresent();
    }
}
