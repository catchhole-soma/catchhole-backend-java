package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** 도메인 검증 후 해소된 경로와 당시 값을 고정한 변경. LLM의 raw patch를 받지 않는다. */
public record AnalysisStateChange(
        String eventId,
        List<String> path,
        JsonNode value,
        boolean remove,
        String operation,
        List<UUID> sourceCandidateIds
) {
    public AnalysisStateChange {
        if (eventId == null || eventId.isBlank() || operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("변경 식별자와 연산은 필수입니다.");
        }
        path = List.copyOf(path);
        sourceCandidateIds = List.copyOf(sourceCandidateIds);
        if (path.size() < 2 || path.stream().anyMatch(String::isBlank)
                || !List.of("characters", "worldSettings", "references").contains(path.getFirst())) {
            throw new IllegalArgumentException("분석 상태의 변경 경로가 올바르지 않습니다.");
        }
        if (remove ? value != null && !value.isNull() : value == null || value.isNull()) {
            throw new IllegalArgumentException("제거 여부와 변경값이 일치하지 않습니다.");
        }
        value = remove ? null : value.deepCopy();
    }

    @Override
    public JsonNode value() {
        return value == null ? null : value.deepCopy();
    }
}
