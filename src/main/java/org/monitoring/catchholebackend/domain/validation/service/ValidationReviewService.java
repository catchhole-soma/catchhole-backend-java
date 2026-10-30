package org.monitoring.catchholebackend.domain.validation.service;

import java.util.UUID;
import org.monitoring.catchholebackend.domain.validation.dto.request.ValidationJudgmentRequest;
import org.monitoring.catchholebackend.domain.validation.dto.response.*;
import org.monitoring.catchholebackend.domain.validation.type.*;
import org.monitoring.catchholebackend.global.common.response.PageResponse;

/** GH312 작가 판단 저장·조회. 마지막 판단 저장도 검토 완료나 설정 추출을 시작하지 않는다(검토 종료는 ValidationReviewCompletionService). */
public interface ValidationReviewService {
    PageResponse<ValidationFindingSummaryResponse> getValidationFindings(Long memberId,UUID workId,UUID reportId,ValidationBranch branch,ValidationJudgmentValue judgment,String outcome,int page,int size);
    ValidationFindingDetailResponse getValidationFinding(Long memberId,UUID workId,UUID reportId,UUID findingId);
    ValidationFindingSummaryResponse saveValidationJudgment(Long memberId,UUID workId,UUID reportId,UUID findingId,ValidationJudgmentRequest request);
    ValidationReviewSummaryResponse getValidationReview(Long memberId,UUID workId,UUID reportId);
    /** GH312 S4: 회차의 모든 report 요약을 최신 revision부터. 인용·근거·판정 payload는 반환하지 않는다. */
    PageResponse<ValidationReportHistoryResponse> getValidationHistory(Long memberId,UUID workId,UUID episodeId,int page,int size);
}
