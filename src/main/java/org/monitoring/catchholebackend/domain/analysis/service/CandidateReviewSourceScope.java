package org.monitoring.catchholebackend.domain.analysis.service;

import org.monitoring.catchholebackend.domain.analysis.repository.CandidateReviewJobMetadata;
import org.monitoring.catchholebackend.domain.analysis.repository.CandidateReviewMetadataRepository;

import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobEpisodeRange;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobType;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Component;

/** Public review scopes identify extraction origins, never the internal comparison jobs. */
@Component
@RequiredArgsConstructor
public class CandidateReviewSourceScope {
    private final AnalysisJobRepository jobs;
    private final CandidateReviewMetadataRepository metadata;
    private final SettingCandidateRepository characters;
    private final WorldSettingCandidateRepository worlds;

    public CandidateReviewJobMetadata validateRead(
            Work work, UUID batchId, UUID sourceAnalysisJobId) {
        if (sourceAnalysisJobId == null) return null;
        return metadata.findByIds(java.util.List.of(sourceAnalysisJobId)).stream()
                .filter(job -> job.jobType() == AnalysisJobType.SETTING_EXTRACTION
                        && Objects.equals(job.workId(), work.getId())
                        && (batchId == null || Objects.equals(job.batchId(), batchId)))
                .findFirst().orElseThrow(this::notFound);
    }

    public AnalysisJobEpisodeRange episodeRangeRead(Work work, UUID batchId,
            CandidateReviewJobMetadata source) {
        if (source == null) return jobs.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        Integer episodeNo = source.settingBookSource() ? null
                : source.sourceEpisodeNo() == null ? source.episodeNo() : source.sourceEpisodeNo();
        return new AnalysisJobEpisodeRange() {
            public Integer getEpisodeStartNo() { return episodeNo; }
            public Integer getEpisodeEndNo() { return episodeNo; }
            public long getEpisodeCount() { return episodeNo == null ? 0 : 1; }
        };
    }

    public java.util.Map<UUID, CandidateReviewJobMetadata> readMetadata(
            java.util.Collection<UUID> ids,
            CandidateReviewJobMetadata source) {
        var remaining = new java.util.HashSet<>(ids);
        var result = new java.util.HashMap<UUID, CandidateReviewJobMetadata>();
        if (source != null) { result.put(source.id(), source); remaining.remove(source.id()); }
        metadata.findByIds(remaining).forEach(row -> result.put(row.id(), row));
        return java.util.Map.copyOf(result);
    }

    public AnalysisJob validate(Work work, UUID batchId, UUID sourceAnalysisJobId, boolean mutation) {
        if (sourceAnalysisJobId == null) return null;
        AnalysisJob job = jobs.findById(sourceAnalysisJobId)
                .filter(j -> j.getJobType() == AnalysisJobType.SETTING_EXTRACTION
                        && Objects.equals(j.getWork().getId(), work.getId())
                        && (batchId == null || j.getBatch() != null && Objects.equals(j.getBatch().getId(), batchId)))
                .orElseThrow(this::notFound);
        if (mutation) assertActionable(job);
        return job;
    }

    public void assertCharacter(Work work, UUID batchId, UUID scope, UUID candidateId, boolean mutation) {
        var candidate = characters.findByIdAndWorkId(candidateId, work.getId()).orElseThrow(() -> new AppException(
                org.monitoring.catchholebackend.domain.character.exception.CharacterErrorCode.SETTING_CANDIDATE_NOT_FOUND));
        assertCandidate(work, batchId, scope, candidate.getAnalysisJob(), mutation);
    }

    public void assertWorld(Work work, UUID batchId, UUID scope, UUID candidateId, boolean mutation) {
        var candidate = worlds.findByIdAndWorkId(candidateId, work.getId()).orElseThrow(() -> new AppException(
                org.monitoring.catchholebackend.domain.worldsetting.exception.WorldSettingErrorCode.WORLD_SETTING_CANDIDATE_NOT_FOUND));
        assertCandidate(work, batchId, scope, candidate.getAnalysisJob(), mutation);
    }

    private void assertCandidate(Work work, UUID batchId, UUID scope, AnalysisJob job, boolean mutation) {
        if (scope == null) {
            if (job != null && job.isSettingBookSource()) throw notFound();
            return;
        }
        validate(work, batchId, scope, mutation);
        if (job == null || !scope.equals(job.getId())) throw notFound();
    }

    public void assertActionable(AnalysisJob origin) {
        if (!origin.isSettingBookSource()) return;
        AnalysisJob latest = jobs.findFirstBySourceSettingBookIdAndJobTypeOrderByCreatedAtDescIdDesc(
                origin.getSourceSettingBook().getId(), AnalysisJobType.SETTING_EXTRACTION).orElseThrow(this::notFound);
        if (!latest.getId().equals(origin.getId())) throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_SUPERSEDED);
        if (!origin.hasCurrentSourceVersion()) throw new AppException(AnalysisJobErrorCode.ANALYSIS_RUN_STATE_CONFLICT);
    }

    public boolean isActionable(AnalysisJob origin) {
        if (origin == null || !origin.isSettingBookSource()) return true;
        if (!origin.hasCurrentSourceVersion()) return false;
        return jobs.findFirstBySourceSettingBookIdAndJobTypeOrderByCreatedAtDescIdDesc(
                origin.getSourceSettingBook().getId(), AnalysisJobType.SETTING_EXTRACTION)
                .filter(latest -> latest.getId().equals(origin.getId())).isPresent();
    }

    public AnalysisJobEpisodeRange episodeRange(Work work, UUID batchId, UUID sourceAnalysisJobId) {
        if (sourceAnalysisJobId == null) return jobs.findEpisodeRangeByWorkIdAndBatchId(work.getId(), batchId);
        AnalysisJob source = validate(work, batchId, sourceAnalysisJobId, false);
        Integer number = source.isSettingBookSource() ? null : source.getSourceEpisodeNo();
        if (number == null && source.getEpisode() != null) number = source.getEpisode().getEpisodeNo();
        Integer episodeNo = number;
        return new AnalysisJobEpisodeRange() {
            public Integer getEpisodeStartNo() { return episodeNo; }
            public Integer getEpisodeEndNo() { return episodeNo; }
            public long getEpisodeCount() { return episodeNo == null ? 0 : 1; }
        };
    }

    private AppException notFound() { return new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND); }
}
