package org.monitoring.catchholebackend.domain.analysis.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 사용자가 허용한 문제 구절만 최대 120 code point로 보존한다. 헤더/예외는 대상이 아니다. */
public record AnalysisFailureExcerpt(String kind, String field, String text, boolean truncated, Integer itemIndex) {
    public AnalysisFailureExcerpt {
        if (text != null && text.codePointCount(0, text.length()) > 120) {
            text = text.substring(0, text.offsetByCodePoints(0, 120));
            truncated = true;
        }
    }
    public boolean isSafe() {
        if (kind == null || field == null || text == null || text.isBlank()
                || itemIndex != null && (itemIndex < 1 || itemIndex > 100000)) return false;
        return switch (kind) {
            case "SOURCE" -> "evidenceQuote".equals(field);
            case "EXPECTED" -> Set.of("relationshipLabel", "relationshipSummary", "subjectOptions").contains(field);
            case "RESPONSE" -> Set.of("relationshipLabel", "relationshipSummary", "relationshipReason", "responseJson").contains(field);
            default -> false;
        };
    }
    public static List<AnalysisFailureExcerpt> fromJson(JsonNode input) {
        if (input == null || !input.isArray()) return List.of();
        var result = new ArrayList<AnalysisFailureExcerpt>();
        for (int i=0; i<Math.min(3, input.size()); i++) {
            var row = input.get(i);
            var index = row.path("itemIndex");
            if (!index.isMissingNode() && !index.isNull() && (!index.isIntegralNumber() || !index.canConvertToInt())) continue;
            var value = new AnalysisFailureExcerpt(text(row, "kind"), text(row, "field"), text(row, "text"),
                    row.path("truncated").isBoolean() && row.path("truncated").booleanValue(), index.isIntegralNumber() ? index.intValue() : null);
            if (value.isSafe()) result.add(value);
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode row, String field) {
        return row.path(field).isTextual() ? row.path(field).stringValue() : null;
    }
}
