package org.monitoring.catchholebackend.domain.analysis.service;

import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisGuideResponse;

public interface AnalysisGuideService {
    AnalysisGuideResponse getGuide(Long memberId);
    AnalysisGuideResponse claimGuide(Long memberId);
    void markAnalysisStarted(Long memberId);
}
