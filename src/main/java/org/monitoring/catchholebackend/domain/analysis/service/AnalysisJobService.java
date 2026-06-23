package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;

public interface AnalysisJobService {

    /**
     * 작품 소유권을 확인하고, 요청에 batchId가 있으면 해당 업로드 batch가 같은 작품에 속하는지 검증한다.
     * 검증된 작품과 batch를 기준으로 PENDING 상태의 AI 분석 작업을 생성한다.
     * 응답에는 분석 작업 정보와 연결된 업로드 파일 목록을 함께 담는다.
     */
    AnalysisJobResponse createAnalysisJob(Long memberId, UUID workId, AnalysisJobCreateRequest request);

    /**
     * 작품 소유권을 확인한 뒤 작품의 분석 작업 목록을 최신 생성순으로 조회한다.
     */
    List<AnalysisJobResponse> getAnalysisJobs(Long memberId, UUID workId);

    /**
     * 작품 소유권과 분석 작업 소속을 확인한 뒤 분석 작업 상세 정보를 조회한다.
     * batch 기반 작업이면 연결된 업로드 파일 목록도 함께 조회해 응답에 포함한다.
     */
    AnalysisJobResponse getAnalysisJob(Long memberId, UUID workId, UUID analysisJobId);
}
