package org.monitoring.catchholebackend.domain.analysis.dto;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;

/** 원문 없이 실패한 검사의 기대값과 관측값만 전달한다. */
public record AnalysisFailureCheck(String field, String rule, String expected, String actual, Integer itemIndex) {
    private static final Set<String> NUMBERS = Set.of("inputTokens", "sourceBytes", "chunkCount", "comparisonBatchCount", "distinctNodeCount",
            "observationCount", "decisionCount", "targetUseCount", "quoteLength", "sourceLength", "startOffset", "endOffset");
    private static final Set<String> REFS = Set.of("observationRefs", "newObservationRefs", "candidateRefs", "selectedSubjectRefs", "sourceNodeRef", "targetNodeRef", "relationshipRef");
    private static final Set<String> BOOLEANS = Set.of("quoteMatchesSource", "sourceHashMatches", "quotesNormalizedMatch", "hasIdentityProofs", "ambiguous");
    private static final Set<String> RULES = Set.of("MAX", "MIN", "EQUALS", "IN_SET", "ORDERED", "UNIQUE", "COVERS", "ABSENT", "DIFFERENT");
    public boolean isSafe() {
        return field != null && rule != null && RULES.contains(rule)
                && (itemIndex == null || itemIndex >= 1 && itemIndex <= 100000) && valid(expected) && valid(actual);
    }
    private boolean valid(String value) {
        if (value == null || value.length() > 150) return false;
        if (NUMBERS.contains(field)) {
            if (!value.matches("-?[0-9]{1,10}")) return false;
            long n = Long.parseLong(value);
            return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE;
        }
        if (REFS.contains(field)) return value.matches("(?:[CSNROHT][1-9][0-9]{0,7}|NONE|INVALID_REF)(?:,(?:[CSNROHT][1-9][0-9]{0,7}|NONE|INVALID_REF)){0,7}");
        if (BOOLEANS.contains(field)) return Set.of("true", "false").contains(value);
        return switch (field) {
            case "temporalScope" -> Set.of("PRESENT", "PAST", "HYPOTHETICAL", "UNKNOWN").contains(value);
            case "operation" -> Set.of("ADD", "MERGE", "UPDATE", "END", "HOLD", "EXCLUDE").contains(value);
            case "targetStatus" -> Set.of("ACTIVE", "ENDED", "MISSING").contains(value);
            default -> false;
        };
    }
    public static List<AnalysisFailureCheck> fromJson(JsonNode input) {
        if (input == null || !input.isArray()) return List.of();
        List<AnalysisFailureCheck> result = new ArrayList<>();
        for (int i = 0; i < Math.min(input.size(), 3); i++) {
            var row = input.get(i);
            var index = row.path("itemIndex");
            if (!index.isMissingNode() && !index.isNull() && (!index.isIntegralNumber() || !index.canConvertToInt())) continue;
            var check = new AnalysisFailureCheck(text(row, "field"), text(row, "rule"), text(row, "expected"), text(row, "actual"),
                    index.isIntegralNumber() ? index.intValue() : null);
            if (check.isSafe()) result.add(check);
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode node, String key) {
        var value = node.path(key);
        return value.isTextual() ? value.stringValue() : null;
    }
}
