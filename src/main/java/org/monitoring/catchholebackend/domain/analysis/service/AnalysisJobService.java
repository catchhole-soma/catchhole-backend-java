package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisBatchSummaryResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobResponse;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisJobProgressResponse;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

public interface AnalysisJobService {

    List<AnalysisJobProgressResponse> getAnalysisJobProgress(Long memberId, UUID workId, List<UUID> analysisJobIds);

    /**
     * 작품 소유권을 확인하고, 요청에 batchId가 있으면 해당 업로드 batch가 같은 작품에 속하는지 검증한다.
     * 검증된 작품과 batch의 각 대상 회차마다 PENDING 상태의 AI 분석 작업을 하나씩 생성한다.
     * 응답에는 생성된 분석 작업 목록과 연결된 업로드 파일 목록을 함께 담는다.
     */
    List<AnalysisJobResponse> createAnalysisJobs(Long memberId, UUID workId, AnalysisJobCreateRequest request);

    /**
     * GH312 검토 종료 release 전용. 단일 신규 회차 기본 요청(SETTING_EXTRACTION, 반영 방식 생략)으로 공개 생성과 같은 검증·S0·토큰 확인을 거쳐
     * 회차 Job 하나를 만든다. 검수 report 차단만 건너뛰므로 같은 트랜잭션·Work 잠금에서 검토 종료 receipt를 만드는 호출자만 사용한다.
     */
    AnalysisJob createReviewReleaseExtractionJob(Long memberId, UUID workId, UUID batchId, UUID episodeId);

    /**
     * 작품 소유권을 확인한 뒤 작품의 분석 작업 목록을 최신 생성순으로 조회한다.
     */
    List<AnalysisJobResponse> getAnalysisJobs(Long memberId, UUID workId);

    /**
     * 작품 소유권을 확인한 뒤 분석 이력을 업로드 batch 단위로 최신 요청순 페이지 조회한다.
     * 같은 회차를 재시도한 이력은 가장 최근 작업만 현재 상태 집계에 사용한다.
     */
    PageResponse<AnalysisBatchSummaryResponse> getAnalysisBatches(
            Long memberId,
            UUID workId,
            int page,
            int size
    );

    /**
     * 작품 소유권과 분석 작업 소속을 확인한 뒤 분석 작업 상세 정보를 조회한다.
     * batch 기반 작업이면 연결된 업로드 파일 목록도 함께 조회해 응답에 포함한다.
     */
    AnalysisJobResponse getAnalysisJob(Long memberId, UUID workId, UUID analysisJobId);

    /**
     * 실패한 기존 작업은 이력으로 유지하고, 서버가 확인한 FAILED 회차마다 새 PENDING 작업을 생성한다.
     * 현재 검수 report가 있는 회차는 그 검토 종료 receipt의 release Job만 재시도하며 새 Job이면 receipt 연결을 옮긴다(GH312).
     */
    List<AnalysisJobResponse> retryFailedAnalysisJob(Long memberId, UUID workId, UUID analysisJobId);
}
