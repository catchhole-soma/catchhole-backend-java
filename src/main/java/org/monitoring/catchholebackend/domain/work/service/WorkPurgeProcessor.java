package org.monitoring.catchholebackend.domain.work.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDataRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeDatabaseResult;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeRequestRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;
import org.monitoring.catchholebackend.global.config.workpurge.WorkPurgeProperties;
import org.monitoring.catchholebackend.global.storage.ObjectStoragePurgeResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkPurgeProcessor {

    private static final String STALE_PROCESSING_ERROR = "WORK_PURGE_PROCESSING_STALE";

    private final WorkPurgeRequestRepository purgeRequestRepository;
    private final WorkPurgeDataRepository purgeDataRepository;
    private final ObjectStorageService objectStorageService;
    private final WorkPurgeProperties properties;
    private final WorkPurgeMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public void processPendingRequests() {
        recoverStaleRequests();
        claimReadyRequests().forEach(this::processRequest);
        refreshOverdueMetric();
    }

    public void deleteExpiredAuditRecords() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            purgeRequestRepository.deleteExpired(now);
        });
    }

    private List<UUID> claimReadyRequests() {
        List<UUID> claimed = transactionTemplate.execute(status -> purgeRequestRepository
                .findReadyForUpdate(
                        WorkPurgeStatus.REQUESTED,
                        LocalDateTime.now(),
                        PageRequest.of(0, properties.getBatchSize())
                )
                .stream()
                .peek(WorkPurgeRequest::startProcessing)
                .map(WorkPurgeRequest::getId)
                .toList());
        return claimed == null ? List.of() : claimed;
    }

    private void processRequest(UUID requestId) {
        UUID workId = transactionTemplate.execute(status -> getRequestForUpdate(requestId).getWorkId());
        if (workId == null) {
            return;
        }
        List<UUID> uploadBatchIds = transactionTemplate.execute(status ->
                purgeDataRepository.findUploadBatchIds(workId));

        ObjectStoragePurgeResult storageResult;
        try {
            storageResult = objectStorageService.purgeWork(
                    workId,
                    uploadBatchIds == null ? List.of() : uploadBatchIds
            );
        } catch (RuntimeException exception) {
            log.error("작품 영구 삭제 저장소 단계 실패: requestId={}", requestId, exception);
            markFailed(requestId, WorkErrorCode.WORK_PURGE_STORAGE_FAILED.getCode(), false);
            return;
        }

        transactionTemplate.executeWithoutResult(status -> getRequestForUpdate(requestId).recordStorageResult(
                storageResult.targetCount(),
                storageResult.deletedCount(),
                storageResult.failedCount()
        ));
        if (!storageResult.isComplete()) {
            markFailed(
                    requestId,
                    WorkErrorCode.WORK_PURGE_STORAGE_FAILED.getCode(),
                    storageResult.deletedCount() > 0
            );
            return;
        }

        try {
            WorkPurgeRequest completed = transactionTemplate.execute(status -> completeDatabasePurge(requestId, workId));
            if (completed != null) {
                metrics.recordCompleted(completed);
            }
        } catch (RuntimeException exception) {
            log.error("작품 영구 삭제 DB 단계 실패: requestId={}", requestId, exception);
            markDatabaseFailed(requestId);
        }
    }

    private WorkPurgeRequest completeDatabasePurge(UUID requestId, UUID workId) {
        WorkPurgeRequest request = getRequestForUpdate(requestId);
        LocalDateTime retentionExpiresAt = LocalDateTime.now().plus(properties.getAuditRetention());
        WorkPurgeDatabaseResult databaseResult = purgeDataRepository.purgeWorkData(workId);
        request.recordDatabaseResult(
                databaseResult.targetCount(),
                databaseResult.deletedCount(),
                databaseResult.failedCount()
        );
        request.complete(retentionExpiresAt);
        return request;
    }

    private void markDatabaseFailed(UUID requestId) {
        transactionTemplate.executeWithoutResult(status -> {
            WorkPurgeRequest request = getRequestForUpdate(requestId);
            request.recordDatabaseResult(0, 0, 1);
            request.fail(
                    WorkErrorCode.WORK_PURGE_DATABASE_FAILED.getCode(),
                    request.getS3DeletedCount() > 0
            );
        });
        metrics.recordFailed();
    }

    private void markFailed(UUID requestId, String errorCode, boolean partialFailure) {
        transactionTemplate.executeWithoutResult(status ->
                getRequestForUpdate(requestId).fail(errorCode, partialFailure));
        metrics.recordFailed();
    }

    private void recoverStaleRequests() {
        transactionTemplate.executeWithoutResult(status -> purgeRequestRepository
                .findStaleProcessingForUpdate(
                        WorkPurgeStatus.PROCESSING,
                        LocalDateTime.now().minus(properties.getStaleProcessing()),
                        PageRequest.of(0, properties.getBatchSize())
                )
                .forEach(request -> request.recoverStaleProcessing(STALE_PROCESSING_ERROR)));
    }

    private void refreshOverdueMetric() {
        long overdue = purgeRequestRepository.countByStatusInAndRequestedAtBefore(
                List.of(
                        WorkPurgeStatus.REQUESTED,
                        WorkPurgeStatus.PROCESSING,
                        WorkPurgeStatus.PARTIAL_FAILED,
                        WorkPurgeStatus.FAILED
                ),
                LocalDateTime.now().minusHours(24)
        );
        metrics.updateOverdueRequests(overdue);
    }

    private WorkPurgeRequest getRequestForUpdate(UUID requestId) {
        return purgeRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalStateException("영구 삭제 요청이 사라졌습니다."));
    }
}
