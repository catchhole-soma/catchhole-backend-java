package org.monitoring.catchholebackend.domain.analysis.processor;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.entity.AnalysisJob;
import org.monitoring.catchholebackend.domain.analysis.type.EvidenceValidationResult;
import org.monitoring.catchholebackend.global.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

/** BOOK 자동 반영은 실제 고정 원문에 존재하는 연속 인용만 근거로 삼는다. */
@Component
@RequiredArgsConstructor
public class SettingBookAutomaticEvidenceValidator {
    private final ObjectStorageService storage;

    public String frozenContent(AnalysisJob job) {
        String content = storage.getText(job.getSourceContentS3Key());
        if (content == null || !storage.contentHash(content).equals(job.getSourceContentHash())) {
            // Source availability is a retryable completion failure, not a permanent candidate judgment.
            throw new IllegalStateException("설정집 고정 원문을 검증할 수 없습니다.");
        }
        return content;
    }

    public boolean matches(String content, JsonNode evidence) {
        return validate(content, evidence) == EvidenceValidationResult.VALID;
    }

    public EvidenceValidationResult validate(String content, JsonNode evidence) {
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) return EvidenceValidationResult.MALFORMED_SPANS;
        for (JsonNode span : evidence) {
            if (!span.isObject() || !span.path("quote").isTextual() || span.path("quote").asText().isBlank()) {
                return EvidenceValidationResult.MALFORMED_SPANS;
            }
        }
        int length = content.codePointCount(0, content.length());
        for (JsonNode span : evidence) {
            String quote = span.path("quote").asText();
            if (span.has("startOffset") && span.has("start_offset") && !span.get("startOffset").equals(span.get("start_offset"))
                    || span.has("endOffset") && span.has("end_offset") && !span.get("endOffset").equals(span.get("end_offset"))) return EvidenceValidationResult.INVALID_OFFSETS;
            JsonNode start = span.has("startOffset") ? span.path("startOffset") : span.path("start_offset");
            JsonNode end = span.has("endOffset") ? span.path("endOffset") : span.path("end_offset");
            boolean startAbsent = start.isNull() || start.isMissingNode(), endAbsent = end.isNull() || end.isMissingNode();
            if (startAbsent && endAbsent) continue;
            if (!start.isIntegralNumber() || !end.isIntegralNumber() || !start.canConvertToInt() || !end.canConvertToInt()) return EvidenceValidationResult.INVALID_OFFSETS;
            int from = start.intValue(), to = end.intValue();
            if (from < 0 || from >= to || to > length || !quote.equals(content.substring(
                    content.offsetByCodePoints(0, from), content.offsetByCodePoints(0, to)))) return EvidenceValidationResult.INVALID_OFFSETS;
        }
        for (JsonNode span : evidence) {
            if (!content.contains(span.path("quote").asText())) return EvidenceValidationResult.QUOTE_NOT_FOUND;
        }
        return EvidenceValidationResult.VALID;
    }
}
