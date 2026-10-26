package org.monitoring.catchholebackend.domain.analysis.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.monitoring.catchholebackend.domain.character.dto.response.CharacterFactEvidenceSpanResponse;

@Schema(description = "앞 회차에서 보류한 참고 정보. 확정 현재값이나 인물 연결 대상이 아닙니다.")
public record WorkerAnalysisReferencePayload(
        String domain, String subjectName, Integer sourceEpisodeNo, String reason,
        String settingName, String value, List<CharacterFactEvidenceSpanResponse> evidenceSpans,
        @JsonInclude(JsonInclude.Include.NON_NULL) String scopeName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String matchedScopeName,
        @JsonInclude(JsonInclude.Include.NON_NULL) String matchedPropertyName,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) org.monitoring.catchholebackend.domain.analysis.dto.response.AnalysisSourceResponse source,
        @JsonInclude(JsonInclude.Include.NON_NULL) Selection selection
) {
    public WorkerAnalysisReferencePayload(String domain, String subjectName, Integer sourceEpisodeNo, String reason, String settingName, String value, List<CharacterFactEvidenceSpanResponse> evidenceSpans, String scopeName, String matchedScopeName, String matchedPropertyName) {
        this(domain, subjectName, sourceEpisodeNo, reason, settingName, value, evidenceSpans, scopeName, matchedScopeName, matchedPropertyName, null, null);
    }
    public WorkerAnalysisReferencePayload(String domain, String subjectName, Integer sourceEpisodeNo, String reason, String settingName, String value, List<CharacterFactEvidenceSpanResponse> evidenceSpans, String scopeName, String matchedScopeName, String matchedPropertyName, AnalysisSourceResponse source) {
        this(domain, subjectName, sourceEpisodeNo, reason, settingName, value, evidenceSpans, scopeName, matchedScopeName, matchedPropertyName, source, null);
    }

    public record Selection(
            List<java.util.UUID> actualSubjectIds, List<String> provisionalSubjectKeys,
            String rawSubjectMention, String candidateKind, String factType, String canonicalFactKey,
            Map<String, Object> valueJson, String worldCategory
    ) {
        public Selection {
            actualSubjectIds = actualSubjectIds == null ? List.of() : List.copyOf(actualSubjectIds);
            provisionalSubjectKeys = provisionalSubjectKeys == null ? List.of() : List.copyOf(provisionalSubjectKeys);
            valueJson = valueJson == null ? null : immutableObject(valueJson);
        }

        private static Map<String, Object> immutableObject(Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put((String) key, immutableValue(value)));
            // JSON object/array nulls must survive defensive copying.
            return Collections.unmodifiableMap(result);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> object) return immutableObject(object);
            if (value instanceof List<?> array) return array.stream().map(Selection::immutableValue).toList();
            return value;
        }
    }
}
