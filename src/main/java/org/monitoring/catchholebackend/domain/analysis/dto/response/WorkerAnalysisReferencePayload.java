package org.monitoring.catchholebackend.domain.analysis.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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
            com.fasterxml.jackson.databind.JsonNode valueJson, String worldCategory
    ) {
        public Selection {
            actualSubjectIds = actualSubjectIds == null ? List.of() : List.copyOf(actualSubjectIds);
            provisionalSubjectKeys = provisionalSubjectKeys == null ? List.of() : List.copyOf(provisionalSubjectKeys);
            valueJson = valueJson == null ? null : valueJson.deepCopy();
        }
        @Override public com.fasterxml.jackson.databind.JsonNode valueJson() {
            return valueJson == null ? null : valueJson.deepCopy();
        }
    }
}
