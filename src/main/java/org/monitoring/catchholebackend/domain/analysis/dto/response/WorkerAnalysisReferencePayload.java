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
        @JsonInclude(JsonInclude.Include.NON_NULL) String matchedPropertyName
) {}
