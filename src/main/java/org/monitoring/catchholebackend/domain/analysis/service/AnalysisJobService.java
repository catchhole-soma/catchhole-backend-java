package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;

//TODO: service 메소드들 행동 역할 주석으로 남겨두기
public interface AnalysisJobService {

    AnalysisJobResponse createAnalysisJob(Long memberId, UUID workId, AnalysisJobCreateRequest request);

    List<AnalysisJobResponse> getAnalysisJobs(Long memberId, UUID workId);

    AnalysisJobResponse getAnalysisJob(Long memberId, UUID workId, UUID analysisJobId);
}
