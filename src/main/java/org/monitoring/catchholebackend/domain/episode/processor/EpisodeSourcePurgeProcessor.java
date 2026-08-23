package org.monitoring.catchholebackend.domain.episode.processor;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.exception.EpisodeErrorCode;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EpisodeSourcePurgeProcessor {

    private final ObjectStorageService objectStorageService;
    private final EpisodePurgeDataRepository purgeDataRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final AnalysisJobRepository analysisJobRepository;

    public void purgeEpisodeSource(
            Episode episode,
            UploadFile sourceFile,
            String retainedContentKey
    ) {
        ObjectStoragePurgeResult storageResult = objectStorageService.purgeEpisodeSource(
                episode.getWork().getId(),
                episode.getEpisodeNo(),
                episode.getContentS3Key(),
                sourceFile == null ? null : sourceFile.getStorageUrl(),
                retainedContentKey
        );
        if (!storageResult.isComplete()) {
            throw new AppException(EpisodeErrorCode.EPISODE_SOURCE_PURGE_FAILED);
        }
        if (sourceFile != null) {
            sourceFile.purgeStoredSource();
        }

        purgeCharacterCandidates(episode.getId());
        purgeWorldSettingCandidates(episode.getId());
        purgeDataRepository.deleteChunks(episode.getId());
    }

    private void purgeCharacterCandidates(UUID episodeId) {
        List<SettingCandidate> candidates = settingCandidateRepository.findAllByAnalysisTargetEpisodeId(episodeId);
        List<SettingCandidate> pendingCandidates = candidates.stream()
                .filter(SettingCandidate::isPendingReview)
                .toList();
        if (!pendingCandidates.isEmpty()) {
            List<UUID> candidateIds = pendingCandidates.stream().map(SettingCandidate::getId).toList();
            analysisJobRepository.findAllBySettingCandidateIdIn(candidateIds)
                    .forEach(AnalysisJob::unlinkSettingCandidate);
            analysisJobRepository.flush();
            settingCandidateRepository.deleteAll(pendingCandidates);
            settingCandidateRepository.flush();
        }
        candidates.stream()
                .filter(candidate -> !candidate.isPendingReview())
                .forEach(SettingCandidate::purgeSourceEvidence);
    }

    private void purgeWorldSettingCandidates(UUID episodeId) {
        List<WorldSettingCandidate> candidates = worldSettingCandidateRepository.findAllBySourceEpisodeId(episodeId);
        List<WorldSettingCandidate> pendingCandidates = candidates.stream()
                .filter(WorldSettingCandidate::isPendingReview)
                .toList();
        if (!pendingCandidates.isEmpty()) {
            List<UUID> candidateIds = pendingCandidates.stream().map(WorldSettingCandidate::getId).toList();
            analysisJobRepository.findAllByWorldSettingCandidateIdIn(candidateIds)
                    .forEach(AnalysisJob::unlinkWorldSettingCandidate);
            analysisJobRepository.flush();
            worldSettingCandidateRepository.deleteAll(pendingCandidates);
            worldSettingCandidateRepository.flush();
        }
        candidates.stream()
                .filter(candidate -> !candidate.isPendingReview())
                .forEach(WorldSettingCandidate::purgeSourceEvidence);
    }
}
