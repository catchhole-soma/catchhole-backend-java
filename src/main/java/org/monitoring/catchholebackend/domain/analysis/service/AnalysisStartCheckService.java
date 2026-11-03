package org.monitoring.catchholebackend.domain.analysis.service;

import java.util.List;
import java.util.UUID;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisJobCreateRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.request.AnalysisStartCheckRequest;
import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisStartCheckResponse;

public interface AnalysisStartCheckService {
    AnalysisStartCheckResponse checkAnalysisStart(Long memberId, UUID workId, AnalysisStartCheckRequest request);

    /** 소유권을 확인하고 Work 잠금을 획득한 생성 트랜잭션 안에서만 호출한다. */
    void applyAnalysisStartDecision(Long memberId, UUID workId, List<Integer> episodeNumbers,
            AnalysisJobCreateRequest request, UUID requestId);
}
