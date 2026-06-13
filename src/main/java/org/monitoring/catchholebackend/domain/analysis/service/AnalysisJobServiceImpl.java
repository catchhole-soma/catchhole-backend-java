package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.exception.AnalysisJobErrorCode;
import org.monitoring.catchholebackend.domain.analysis.mapper.AnalysisJobMapper;
import org.monitoring.catchholebackend.domain.analysis.repository.AnalysisJobRepository;
import org.monitoring.catchholebackend.domain.upload.entity.UploadBatch;
import org.monitoring.catchholebackend.domain.upload.entity.UploadFile;
import org.monitoring.catchholebackend.domain.upload.repository.UploadBatchRepository;
import org.monitoring.catchholebackend.domain.upload.repository.UploadFileRepository;
import org.monitoring.catchholebackend.domain.work.entity.Work;
import org.monitoring.catchholebackend.domain.work.repository.WorkRepository;
import org.monitoring.catchholebackend.global.exception.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisJobServiceImpl implements AnalysisJobService {

    private final AnalysisJobRepository analysisJobRepository;
    private final WorkRepository workRepository;
    private final UploadBatchRepository uploadBatchRepository;
    private final UploadFileRepository uploadFileRepository;
    private final AnalysisJobMapper analysisJobMapper;

    @Override
    @Transactional
    public AnalysisJobResponse createAnalysisJob(Long memberId, UUID workId, AnalysisJobCreateRequest request) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        UploadBatch batch = getBatchInWork(request.batchId(), work);

        AnalysisJob analysisJob = AnalysisJob.create(work, batch, null, request.jobType());
        AnalysisJob savedAnalysisJob = analysisJobRepository.save(analysisJob);

        return analysisJobMapper.toResponse(savedAnalysisJob, getUploadFiles(savedAnalysisJob));
    }

    @Override
    public List<AnalysisJobResponse> getAnalysisJobs(Long memberId, UUID workId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        List<AnalysisJob> analysisJobs = analysisJobRepository.findAllByWorkIdOrderByCreatedAtDesc(work.getId());
        return analysisJobMapper.toResponseList(analysisJobs, getUploadFilesByBatchId(analysisJobs));
    }

    @Override
    public AnalysisJobResponse getAnalysisJob(Long memberId, UUID workId, UUID analysisJobId) {
        Work work = workRepository.getOwnedWork(workId, memberId);
        AnalysisJob analysisJob = analysisJobRepository.findByIdAndWorkId(analysisJobId, work.getId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_NOT_FOUND , "해당 아이디가 존재하지 않습니다."));
        return analysisJobMapper.toResponse(analysisJob, getUploadFiles(analysisJob));
    }

    private UploadBatch getBatchInWork(UUID batchId, Work work) {
        if (batchId == null) {
            return null;
        }
        return uploadBatchRepository.findByIdAndWorkId(batchId, work.getId())
                .orElseThrow(() -> new AppException(AnalysisJobErrorCode.ANALYSIS_JOB_TARGET_NOT_FOUND));
    }

    private List<UploadFile> getUploadFiles(AnalysisJob analysisJob) {
        if (analysisJob.getBatch() == null) {
            return List.of();
        }
        return uploadFileRepository.findAllByBatchIdOrderByCreatedAtAsc(analysisJob.getBatch().getId());
    }

    private Map<UUID, List<UploadFile>> getUploadFilesByBatchId(List<AnalysisJob> analysisJobs) {
        List<UUID> batchIds = analysisJobs.stream()
                .map(AnalysisJob::getBatch)
                .filter(batch -> batch != null)
                .map(UploadBatch::getId)
                .distinct()
                .toList();
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return uploadFileRepository.findAllByBatchIdIn(batchIds)
                .stream()
                .collect(Collectors.groupingBy(uploadFile -> uploadFile.getBatch().getId()));
    }
}
