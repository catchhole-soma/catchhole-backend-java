package org.monitoring.catchholebackend.domain.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.monitoring.catchholebackend.domain.work.entity.Work;

/** 현재 확정 설정의 S0를 제공한다. 비교 worker/service에 의존하지 않는다. */
public interface AnalysisStateSource {
    String domain();

    JsonNode capture(Work work);
}
