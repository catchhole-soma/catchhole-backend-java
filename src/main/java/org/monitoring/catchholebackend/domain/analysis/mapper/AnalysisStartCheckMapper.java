package org.monitoring.catchholebackend.domain.analysis.mapper;

import org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisStartCheckResponse;
import org.monitoring.catchholebackend.domain.analysis.service.AnalysisCandidateReviewGate;
import org.monitoring.catchholebackend.domain.relationship.service.RelationshipHoldReviewGate;
import org.springframework.stereotype.Component;

@Component
public class AnalysisStartCheckMapper {
    public AnalysisStartCheckResponse toResponse(long activeEarlierAnalysisCount, long manualReviewCount,
            AnalysisCandidateReviewGate.Snapshot candidates, RelationshipHoldReviewGate.Snapshot relationships,
            String revision) {
        return new AnalysisStartCheckResponse(activeEarlierAnalysisCount, manualReviewCount,
                candidates.ambiguousCharacterCount(), candidates.ambiguousWorldCount(), relationships.count(), revision);
    }
}
