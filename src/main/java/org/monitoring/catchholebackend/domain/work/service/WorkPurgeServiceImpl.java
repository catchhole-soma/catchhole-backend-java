package org.monitoring.catchholebackend.domain.work.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.aitoken.service.AiTokenService;
import org.monitoring.catchholebackend.domain.aitoken.type.AiTokenUsageOutcome;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.domain.work.dto.request.WorkPurgeCreateRequest;
import org.monitoring.catchholebackend.domain.work.dto.response.WorkPurgeResponse;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.entity.WorkPurgeRequest;
import org.monitoring.catchholebackend.domain.work.exception.WorkErrorCode;
import org.monitoring.catchholebackend.domain.work.mapper.WorkPurgeMapper;
import org.monitoring.catchholebackend.domain.work.repository.WorkPurgeRequestRepository;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.domain.work.type.WorkPurgeStatus;
import org.monitoring.catchholebackend.global.config.workpurge.WorkPurgeProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkPurgeServiceImpl implements WorkPurgeService, MemberWorkPurgeCoordinator {

    private static final List<AnalysisJobStatus> ACTIVE_JOB_STATUSES =
            List.of(AnalysisJobStatus.PENDING, AnalysisJobStatus.RUNNING);

    private final WorkPurgeRequestRepository purgeRequestRepository;
    private final WorkRepository workRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final AiTokenService aiTokenService;
    private final WorkPurgeMapper purgeMapper;
    private final WorkPurgeProperties properties;

    @Override
    @Transactional
    public WorkPurgeResponse requestPurge(Long memberId, UUID workId, WorkPurgeCreateRequest request) {
        return purgeMapper.toResponse(resolvePurgeRequest(memberId, workId).request());
    }

    @Override
    @Transactional
    public MemberWorkPurgeProgress coordinateForWithdrawal(Long memberId) {
        int createdRequestCount = 0;
        for (UUID workId : workRepository.findAllIdsByMemberId(memberId)) {
            PurgeRequestResolution resolution = resolvePurgeRequest(memberId, workId);
            if (resolution.created()) {
                createdRequestCount++;
            }
        }

        List<WorkPurgeRequest> retryableRequests = purgeRequestRepository
                .findAllByMemberIdAndStatusInForUpdate(
                        memberId,
                        List.of(WorkPurgeStatus.FAILED, WorkPurgeStatus.PARTIAL_FAILED)
                );
        retryableRequests.forEach(WorkPurgeRequest::retry);

        return new MemberWorkPurgeProgress(
                workRepository.countByMemberId(memberId),
                createdRequestCount,
                retryableRequests.size()
        );
    }

    private PurgeRequestResolution resolvePurgeRequest(Long memberId, UUID workId) {
        WorkPurgeRequest existing = purgeRequestRepository.findByMemberIdAndWorkId(memberId, workId)
                .orElse(null);
        if (existing != null) {
            return new PurgeRequestResolution(existing, false);
        }

        Work work = workRepository.findByIdAndMemberIdForUpdate(workId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
        existing = purgeRequestRepository.findByMemberIdAndWorkId(memberId, workId).orElse(null);
        if (existing != null) {
            return new PurgeRequestResolution(existing, false);
        }

        work.startPurging();
        List<AnalysisJob> activeJobs = analysisJobRepository.findAllActiveByWorkIdForUpdate(
                workId,
                ACTIVE_JOB_STATUSES
        );
        boolean runningJobCanceled = activeJobs.stream()
                .anyMatch(job -> job.getStatus() == AnalysisJobStatus.RUNNING);
        activeJobs.forEach(job -> {
            job.cancelForWorkPurge();
            aiTokenService.releaseReservedForAnalysisJob(
                    job.getId(),
                    AiTokenUsageOutcome.WORK_PURGE_CANCELED
            );
        });

        LocalDateTime workerDrainUntil = runningJobCanceled
                ? LocalDateTime.now().plus(properties.getWorkerDrain())
                : null;
        WorkPurgeRequest purgeRequest = purgeRequestRepository.save(
                WorkPurgeRequest.request(memberId, workId, workerDrainUntil)
        );
        return new PurgeRequestResolution(purgeRequest, true);
    }

    @Override
    public WorkPurgeResponse getPurgeRequest(Long memberId, UUID requestId) {
        WorkPurgeRequest purgeRequest = purgeRequestRepository.findByIdAndMemberId(requestId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_PURGE_NOT_FOUND));
        return purgeMapper.toResponse(purgeRequest);
    }

    @Override
    public WorkPurgeResponse getPurgeRequestByWork(Long memberId, UUID workId) {
        WorkPurgeRequest purgeRequest = purgeRequestRepository.findByMemberIdAndWorkId(memberId, workId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_PURGE_NOT_FOUND));
        return purgeMapper.toResponse(purgeRequest);
    }

    @Override
    @Transactional
    public WorkPurgeResponse retryPurge(Long memberId, UUID requestId) {
        WorkPurgeRequest purgeRequest = purgeRequestRepository.findByIdAndMemberIdForUpdate(requestId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_PURGE_NOT_FOUND));
        if (!purgeRequest.getStatus().canRetry()) {
            throw new AppException(WorkErrorCode.WORK_PURGE_RETRY_NOT_ALLOWED);
        }
        purgeRequest.retry();
        return purgeMapper.toResponse(purgeRequest);
    }

    private record PurgeRequestResolution(WorkPurgeRequest request, boolean created) {
    }
}
