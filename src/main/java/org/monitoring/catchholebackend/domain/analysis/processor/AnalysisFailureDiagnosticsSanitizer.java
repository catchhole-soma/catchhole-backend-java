package org.monitoring.catchholebackend.domain.analysis.processor;

import tools.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.analysis.dto.AnalysisFailureDiagnostics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 부가 진단 오류는 원래 실패 보고를 거절하지 않는다. */
@Component
@RequiredArgsConstructor
public class AnalysisFailureDiagnosticsSanitizer {
    private final JdbcTemplate jdbc;

    public AnalysisFailureDiagnostics sanitize(JsonNode input, UUID workId) {
        if (input == null || !input.isObject() || !input.path("schemaVersion").isIntegralNumber()
                || !input.path("schemaVersion").canConvertToInt()
                || input.path("schemaVersion").intValue() != 1
                || !input.path("origin").isTextual()
                || !AnalysisFailureDiagnostics.ORIGINS.contains(input.path("origin").stringValue())) return null;
        String origin = input.path("origin").stringValue();
        var result = new AnalysisFailureDiagnostics(1, origin,
                code(input, "stage", AnalysisFailureDiagnostics.STAGES),
                code(input, "reasonCode", AnalysisFailureDiagnostics.REASONS),
                code(input, "sourceErrorCode", AnalysisFailureDiagnostics.SOURCE_CODES),
                number(input, "httpStatus", 100, 599),
                bounded(input, "requestId", origin.equals("PROVIDER") ? "[A-Za-z0-9_-]{1,128}"
                        : "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                bounded(input, "traceId", "[0-9a-f]{32}"), uuid(input, "sourceAnalysisJobId"),
                uuid(input, "sourceCandidateId"), uuid(input, "sourceEpisodeId"),
                number(input, "sourceEpisodeNo", 1, Integer.MAX_VALUE));
        return verifiedSource(result, workId) ? result : result.withoutSourceIdentifiers();
    }

    private boolean verifiedSource(AnalysisFailureDiagnostics value, UUID workId) {
        if (value.sourceAnalysisJobId() == null) return false;
        try {
            var jobs = jdbc.queryForList("select j.id, j.episode_id, j.source_episode_no from analysis_jobs j left join episodes e on e.id = j.episode_id where j.id = ? and j.work_id = ? and (j.episode_id is null or e.work_id = ?)",
                    value.sourceAnalysisJobId(), workId, workId);
            if (jobs.size() != 1) return false;
            var job = jobs.getFirst();
            if (value.sourceEpisodeId() != null && !Objects.equals(value.sourceEpisodeId(), job.get("episode_id"))) return false;
            if (value.sourceEpisodeNo() != null && !Objects.equals(value.sourceEpisodeNo(), job.get("source_episode_no"))) return false;
            if (value.sourceCandidateId() == null) return true;
            return !jdbc.queryForList("select c.id from setting_candidates c join analysis_jobs j on j.id = c.analysis_job_id "
                            + "where c.id = ? and c.work_id = ? and c.analysis_job_id = ? and c.episode_id is not distinct from j.episode_id "
                            + "union all select c.id from world_setting_candidates c join analysis_jobs j on j.id = c.analysis_job_id "
                            + "where c.id = ? and c.work_id = ? and c.analysis_job_id = ? and c.source_episode_id is not distinct from j.episode_id",
                    value.sourceCandidateId(), workId, value.sourceAnalysisJobId(),
                    value.sourceCandidateId(), workId, value.sourceAnalysisJobId()).isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String code(JsonNode input, String field, Set<String> allowed) {
        JsonNode value = input.path(field);
        return value.isTextual() && allowed.contains(value.stringValue()) ? value.stringValue() : "UNKNOWN";
    }
    private static String bounded(JsonNode input, String field, String pattern) {
        JsonNode value = input.path(field);
        return value.isTextual() && value.stringValue().length() <= 128 && value.stringValue().matches(pattern) ? value.stringValue() : null;
    }
    private static Integer number(JsonNode input, String field, int min, int max) {
        JsonNode value = input.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= min && value.intValue() <= max ? value.intValue() : null;
    }
    private static UUID uuid(JsonNode input, String field) {
        String value = bounded(input, field, "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        return value == null ? null : UUID.fromString(value);
    }
}
