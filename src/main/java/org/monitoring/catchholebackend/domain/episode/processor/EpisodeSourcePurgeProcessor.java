package org.monitoring.catchholebackend.domain.episode.processor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.monitoring.catchholebackend.domain.character.repository.SettingCandidateRepository;
import org.monitoring.catchholebackend.domain.episode.entity.Episode;
import org.monitoring.catchholebackend.domain.episode.entity.EpisodeSourcePurgeRequest;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodePurgeDataRepository;
import org.monitoring.catchholebackend.domain.episode.repository.EpisodeSourcePurgeRequestRepository;
import org.monitoring.catchholebackend.domain.episode.type.EpisodeSourcePurgeStatus;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.worldsetting.entity.WorldSettingCandidate;
import org.monitoring.catchholebackend.domain.worldsetting.repository.WorldSettingCandidateRepository;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class EpisodeSourcePurgeProcessor {

    private static final int PROCESS_BATCH_SIZE = 10;
    private static final long STALE_PROCESSING_MINUTES = 15;
    private static final String STORAGE_ERROR = "EPISODE_SOURCE_PURGE_STORAGE_FAILED";
    private static final String DATABASE_ERROR = "EPISODE_SOURCE_PURGE_DATABASE_FAILED";
    private static final String STALE_PROCESSING_ERROR = "EPISODE_SOURCE_PURGE_PROCESSING_STALE";

    private final ObjectStorageService objectStorageService;
    private final EpisodePurgeDataRepository purgeDataRepository;
    private final EpisodeSourcePurgeRequestRepository purgeRequestRepository;
    private final SettingCandidateRepository settingCandidateRepository;
    private final WorldSettingCandidateRepository worldSettingCandidateRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final UploadFileRepository uploadFileRepository;
    private final WorkRepository workRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * 새 원문 메타데이터와 같은 트랜잭션에 이전 원문 정리 대상을 남긴다.
     * 실제 S3/파생 데이터 파기는 커밋 후 이벤트와 스케줄러가 수행한다.
     */
    public UUID requestReplacementPurge(
            Episode episode,
            UploadFile previousSourceFile,
            String retainedContentKey
    ) {
        return purgeRequestRepository.save(EpisodeSourcePurgeRequest.requestReplacement(
                episode,
                previousSourceFile,
                retainedContentKey
        )).getId();
    }

    /** Episode tombstone과 같은 트랜잭션에 삭제 대상을 남겨 저장소 파기를 재시도 가능하게 한다. */
    public UUID requestDeletionPurge(Episode episode, UploadFile previousSourceFile) {
        return purgeRequestRepository.save(EpisodeSourcePurgeRequest.requestDeletion(
                episode,
                previousSourceFile
        )).getId();
    }

    public void processPendingRequests() {
        recoverStaleRequests();
        for (int processedCount = 0; processedCount < PROCESS_BATCH_SIZE; processedCount++) {
            UUID requestId = claimNextRequest();
            if (requestId == null) {
                return;
            }
            if (!processClaimedRequest(requestId)) {
                return;
            }
        }
    }

    /** 커밋 직후 이벤트가 특정 요청을 바로 처리한다. 이미 스케줄러가 선점했다면 건너뛴다. */
    public void processRequest(UUID requestId) {
        if (claimRequest(requestId)) {
            processClaimedRequest(requestId);
        }
    }

    private UUID claimNextRequest() {
        TransactionTemplate transaction = newTransaction();
        return transaction.execute(status -> purgeRequestRepository.findReadyForUpdate(
                        EpisodeSourcePurgeStatus.REQUESTED,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .map(request -> {
                    request.startProcessing();
                    return request.getId();
                })
                .orElse(null));
    }

    private boolean claimRequest(UUID requestId) {
        TransactionTemplate transaction = newTransaction();
        return Boolean.TRUE.equals(transaction.execute(status -> purgeRequestRepository
                .findByIdForUpdate(requestId)
                .filter(request -> request.getStatus() == EpisodeSourcePurgeStatus.REQUESTED)
                .map(request -> {
                    request.startProcessing();
                    return true;
                })
                .orElse(false)));
    }

    private boolean processClaimedRequest(UUID requestId) {
        PurgeTarget target = loadTarget(requestId);
        if (target == null) {
            return true;
        }

        ObjectStoragePurgeResult storageResult;
        try {
            storageResult = objectStorageService.purgeEpisodeSource(
                    target.workId(),
                    target.previousEpisodeNo(),
                    target.previousContentKey(),
                    target.previousSourceStorageUrl(),
                    target.retainedContentKey()
            );
        } catch (RuntimeException exception) {
            log.error("회차 원문 저장소 파기 실패: requestId={}", requestId, exception);
            markForRetry(requestId, STORAGE_ERROR);
            return false;
        }
        if (!storageResult.isComplete()) {
            log.warn(
                    "회차 원문 일부 파기 실패: requestId={}, target={}, deleted={}, failed={}",
                    requestId,
                    storageResult.targetCount(),
                    storageResult.deletedCount(),
                    storageResult.failedCount()
            );
            markForRetry(requestId, STORAGE_ERROR);
            return false;
        }

        try {
            TransactionTemplate transaction = newTransaction();
            transaction.executeWithoutResult(status -> completeDatabasePurge(
                    requestId,
                    target.workId()
            ));
            return true;
        } catch (RuntimeException exception) {
            log.error("회차 원문 DB 정리 실패: requestId={}", requestId, exception);
            markForRetry(requestId, DATABASE_ERROR);
            return false;
        }
    }

    private PurgeTarget loadTarget(UUID requestId) {
        TransactionTemplate transaction = newTransaction();
        return transaction.execute(status -> purgeRequestRepository.findById(requestId)
                .map(request -> new PurgeTarget(
                        request.getWorkId(),
                        request.getPreviousEpisodeNo(),
                        request.getPreviousContentKey(),
                        request.getPreviousSourceStorageUrl(),
                        request.getRetainedContentKey()
                ))
                .orElse(null));
    }

    private void completeDatabasePurge(UUID requestId, UUID workId) {
        // 모든 후보 검토 API와 같은 Work 잠금을 먼저 잡아 사용자 결정과 정리를 직렬화한다.
        if (workRepository.findByIdForUpdate(workId).isEmpty()) {
            return;
        }
        EpisodeSourcePurgeRequest request = purgeRequestRepository.findByIdForUpdate(requestId).orElse(null);
        if (request == null) {
            return;
        }
        UUID previousSourceFileId = request.getPreviousSourceFileId();
        if (previousSourceFileId != null) {
            uploadFileRepository.findById(previousSourceFileId).ifPresent(UploadFile::purgeStoredSource);
        }
        UUID episodeId = request.getEpisode().getId();
        purgeCharacterCandidates(episodeId);
        purgeWorldSettingCandidates(episodeId);
        purgeDataRepository.deleteChunks(episodeId);
        purgeRequestRepository.delete(request);
    }

    private void markForRetry(UUID requestId, String errorCode) {
        try {
            TransactionTemplate transaction = newTransaction();
            transaction.executeWithoutResult(status -> purgeRequestRepository
                    .findByIdForUpdate(requestId)
                    .ifPresent(request -> request.retry(errorCode)));
        } catch (RuntimeException exception) {
            log.error("회차 원문 파기 재시도 상태 저장 실패: requestId={}", requestId, exception);
        }
    }

    private void recoverStaleRequests() {
        TransactionTemplate transaction = newTransaction();
        transaction.executeWithoutResult(status -> purgeRequestRepository.findStaleProcessingForUpdate(
                        EpisodeSourcePurgeStatus.PROCESSING,
                        LocalDateTime.now().minusMinutes(STALE_PROCESSING_MINUTES),
                        PageRequest.of(0, PROCESS_BATCH_SIZE)
                )
                .forEach(request -> request.recoverStaleProcessing(STALE_PROCESSING_ERROR)));
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
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

    private record PurgeTarget(
            UUID workId,
            int previousEpisodeNo,
            String previousContentKey,
            String previousSourceStorageUrl,
            String retainedContentKey
    ) {
    }
}
