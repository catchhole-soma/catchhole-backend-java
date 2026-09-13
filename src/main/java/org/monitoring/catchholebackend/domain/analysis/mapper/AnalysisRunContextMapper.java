package org.monitoring.catchholebackend.domain.analysis.mapper;

import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisContextPayload;
import org.monitoring.catchholebackend.domain.analysis.dto.response.WorkerAnalysisReferencePayload;
import org.monitoring.catchholebackend.domain.character.mapper.CharacterFactEvidenceMapper;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisRunContextMapper {
    private final CharacterFactEvidenceMapper evidenceMapper;
    public WorkerAnalysisContextPayload toResponse(AnalysisJob job) {
        return !job.isOrderedProvisional() ? null : new WorkerAnalysisContextPayload(job.getAnalysisRunId(),
                job.getRunGeneration(), job.getInputStateHash(), job.getSourceContentHash(), AnalysisStateJournal.FORMAT_VERSION,
                references(job.getAutomaticInputState()));
    }

    private List<WorkerAnalysisReferencePayload> references(JsonNode input) {
        if (input == null) return List.of();
        List<WorkerAnalysisReferencePayload> result = new ArrayList<>();
        // PostgreSQL jsonb는 객체 키 순서를 보존하지 않는다. claim 직후와 DB 재조회 후에도
        // 동일 입력 문맥이 되도록 고정된 참조 식별자 순서로 목록을 만든다.
        JsonNode references = input.path("references");
        List<String> referenceIds = new ArrayList<>();
        references.fieldNames().forEachRemaining(referenceIds::add);
        referenceIds.sort(String::compareTo);
        for (String referenceId : referenceIds) {
            JsonNode reference = references.path(referenceId);
            // 사용자 거절 정책의 내부 식별자/본문은 이 공용 참고 DTO로 노출하지 않는다.
            if (!reference.hasNonNull("reason") || !reference.has("subjectName")
                    || !reference.path("sourceEpisodeNo").isIntegralNumber() || reference.path("sourceEpisodeNo").asInt() < 1
                    || !List.of("characters", "worldSettings").contains(reference.path("domain").asText())) continue;
            result.add(new WorkerAnalysisReferencePayload(reference.path("domain").asText(),
                    nullableText(reference, "subjectName"), reference.path("sourceEpisodeNo").isIntegralNumber()
                    ? reference.path("sourceEpisodeNo").asInt() : null, reference.path("reason").asText(),
                    nullableText(reference, "settingName"), nullableText(reference, "value"),
                    evidenceMapper.toEvidenceSpans(reference.get("evidenceSpans")),
                    nullableText(reference, "scopeName"), nullableText(reference, "matchedScopeName"),
                    nullableText(reference, "matchedPropertyName")));
        }
        return List.copyOf(result);
    }

    private String nullableText(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }
}
