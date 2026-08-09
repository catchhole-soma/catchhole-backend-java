package org.monitoring.catchholebackend.domain.analysis.service;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.analysis.type.AnalysisJobStatus;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisJobLeaseServiceImpl implements AnalysisJobLeaseService {

    private final AnalysisJobRepository analysisJobRepository;

    @Override
    @Transactional
    public AnalysisJob getRunningAnalysisJobForUpdate(UUID analysisJobId, UUID leaseToken) {
        AnalysisJob analysisJob = analysisJobRepository.findByIdForUpdate(analysisJobId)
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND));
        if (analysisJob.getStatus() != AnalysisJobStatus.RUNNING) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_STATUS_CONFLICT);
        }
        if (!analysisJob.hasLease(leaseToken) || analysisJob.isLeaseExpired(LocalDateTime.now())) {
            throw new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_LEASE_CONFLICT);
        }
        return analysisJob;
    }
}
