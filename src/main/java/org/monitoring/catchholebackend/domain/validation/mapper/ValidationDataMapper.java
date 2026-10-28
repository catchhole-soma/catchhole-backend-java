package org.monitoring.catchholebackend.domain.validation.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.monitoring.catchholebackend.domain.analysis.processor.AnalysisStateJournal;
import org.monitoring.catchholebackend.domain.validation.entity.ValidationReport;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class ValidationDataMapper {
    private final AnalysisStateJournal journal;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Spring Boot 4의 Jackson 3 경계에 Jackson 2 tree 객체 대신 표준 JSON 값을 전달한다. */
    public Map<String, Object> toTransport(JsonNode response) {
        return objectMapper.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public ObjectNode toResponse(ValidationReport report) {
        return JsonNodeFactory.instance.objectNode().put("formatVersion", 1)
                .put("reportId", report.getId().toString()).put("baselineRevision", 1)
                .put("baselineHash", report.getBaselineHash()).put("targetHash", report.getSourceContentHash())
                .put("manifestHash", journal.hash(report.getBaselineSeed().path("sources"))).put("status", "READY");
    }

    public LinkedHashMap<String, ObjectNode> toSourceReferences(ValidationReport report) {
        var result = new LinkedHashMap<String, ObjectNode>();
        ObjectNode target = report.getBaselineSeed().path("target").deepCopy();
        target.put("sourceKind", "EPISODE").put("sourceRef", "T1"); result.put("T1", target);
        int index = 1;
        for (JsonNode source : report.getBaselineSeed().path("sources")) {
            ObjectNode copy = source.deepCopy(); String ref = "R" + index++;
            copy.put("sourceRef", ref); result.put(ref, copy);
        }
        return result;
    }

    public ArrayNode toEntries(JsonNode entries, int offset, int limit) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (int index = offset; index < Math.min(entries.size(), (long) offset + limit); index++) {
            ObjectNode entry = entries.get(index).deepCopy();
            entry.put("entryRef", "B" + (index + 1)); result.add(entry);
        }
        return result;
    }
}
