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
import org.monitoring.catchholebackend.global.config.workpurge.WorkPurgeProperties;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkPurgeServiceImpl implements WorkPurgeService {

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
        WorkPurgeRequest existing = purgeRequestRepository.findByMemberIdAndWorkId(memberId, workId)
                .orElse(null);
        if (existing != null) {
            return purgeMapper.toResponse(existing);
        }

        Work work = workRepository.findByIdAndMemberIdForUpdate(workId, memberId)
                .orElseThrow(() -> new AppException(WorkErrorCode.WORK_NOT_FOUND));
        existing = purgeRequestRepository.findByMemberIdAndWorkId(memberId, workId).orElse(null);
        if (existing != null) {
            return purgeMapper.toResponse(existing);
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
        return purgeMapper.toResponse(purgeRequest);
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
}
