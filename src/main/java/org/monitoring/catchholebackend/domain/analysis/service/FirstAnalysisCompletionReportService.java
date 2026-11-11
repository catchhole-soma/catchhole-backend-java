package org.monitoring.catchholebackend.domain.analysis.service;

import org.monitoring.catchholebackend.domain.analysis.dto.response.FirstAnalysisCompletionReportResponse;

public interface FirstAnalysisCompletionReportService {
    FirstAnalysisCompletionReportResponse getFirstAnalysisCompletionReport(Long memberId);
    FirstAnalysisCompletionReportResponse claimFirstAnalysisCompletionReport(Long memberId);
}
