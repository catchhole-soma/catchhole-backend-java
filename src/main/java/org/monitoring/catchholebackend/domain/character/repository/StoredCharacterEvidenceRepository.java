package org.monitoring.catchholebackend.domain.character.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.monitoring.catchholebackend.domain.character.entity.SettingCandidate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 저장 시 원문 청크와 대조한 결과만 읽는다. S3나 현재 청크의 존재에 의존하지 않는다. */
@Repository
@RequiredArgsConstructor
public class StoredCharacterEvidenceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public Evidence find(SettingCandidate candidate) {
        if (candidate.getId() == null || candidate.getAnalysisJob() == null) return Evidence.unavailable();
        var rows = jdbc.query("""
                select c.stored_evidence_validation as receipt,
                       j.source_content_hash, j.source_content_s3_key, j.source_content_s3_version,
                       (j.source_reference_invalidated_at is not null or
                         (j.source_kind='EPISODE' and (e.id is null or e.status='ARCHIVED'
                           or e.content_s3_key is distinct from j.source_content_s3_key
                           or e.content_hash is distinct from j.source_content_hash
                           or e.content_s3_version is distinct from j.source_content_s3_version
                           or exists(select 1 from episode_source_purge_requests p
                              where p.episode_id=e.id and p.retained_content_key is distinct from j.source_content_s3_key)))) as invalidated
                from setting_candidates c join analysis_jobs j on j.id=c.analysis_job_id and j.work_id=c.work_id
                left join episodes e on e.id=j.episode_id and e.work_id=j.work_id
                where c.id=? and c.work_id=? and c.analysis_job_id=?
                """, (rs, index) -> {
            if (rs.getBoolean("invalidated")) return Evidence.purged();
            JsonNode receipt = read(rs.getString("receipt"));
            if (receipt.path("version").asInt() != 1 || !receipt.path("input").equals(input(candidate))
                    || !Objects.equals(receipt.path("sourceHash").asText(null), rs.getString("source_content_hash"))
                    || !Objects.equals(receipt.path("sourceKey").asText(null), rs.getString("source_content_s3_key"))
                    || !Objects.equals(receipt.path("sourceVersion").asText(null), rs.getString("source_content_s3_version"))
                    || !Objects.equals(candidate.getAnalysisJob().getSourceContentHash(), rs.getString("source_content_hash"))
                    || !Objects.equals(candidate.getAnalysisJob().getSourceContentS3Key(), rs.getString("source_content_s3_key"))
                    || !Objects.equals(candidate.getAnalysisJob().getSourceContentS3Version(), rs.getString("source_content_s3_version"))) {
                return Evidence.unavailable();
            }
            return new Evidence(false, true, receipt.path("evidenceSpans"),
                    receipt.path("narrativeTimeValid").asBoolean(), receipt.path("identityEvidence"), receipt.path("narrativeSpans"));
        }, candidate.getId(), candidate.getWork().getId(), candidate.getAnalysisJob().getId());
        return rows.isEmpty() ? Evidence.unavailable() : rows.getFirst();
    }

    /** JSON structural equality avoids cross-language JSON text/digest differences. */
    private JsonNode input(SettingCandidate candidate) {
        var input = json.createArrayNode();
        input.add(candidate.getWork().getId().toString());
        input.add(candidate.getEpisode() == null ? null : candidate.getEpisode().getId().toString());
        input.add(candidate.getAnalysisJob().getId().toString());
        input.add(candidate.getSourceKind().name());
        input.add(candidate.getSourceSettingBook() == null ? null : candidate.getSourceSettingBook().getId().toString());
        input.add(candidate.getSourceSettingBookRevision() == null ? null : candidate.getSourceSettingBookRevision().getId().toString());
        input.add(candidate.getSourceChunkId() == null ? null : candidate.getSourceChunkId().toString());
        input.add(candidate.getSourceContentS3Key());
        input.add(value(candidate.getEvidenceSpans()));
        input.add(value(candidate.getNarrativeTime()));
        input.add(candidate.getRawAiResultJson() == null ? value(null) : value(candidate.getRawAiResultJson().get("setting_book_identity_proof")));
        return input;
    }

    private JsonNode read(String text) {
        if (text == null) return value(null);
        try { return json.readTree(text); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("저장 근거 검증 기록이 올바르지 않습니다.", error); }
    }
    private JsonNode value(JsonNode node) { return node == null ? JsonNodeFactory.instance.nullNode() : node; }

    public record Evidence(boolean invalidated, boolean verified, JsonNode spans, boolean narrativeTimeValid, JsonNode identitySpans, JsonNode narrativeSpans) {
        public Evidence(boolean invalidated, boolean verified, JsonNode spans, boolean narrativeTimeValid, JsonNode identitySpans) {
            this(invalidated,verified,spans,narrativeTimeValid,identitySpans,JsonNodeFactory.instance.arrayNode());
        }
        public static Evidence unavailable() { return new Evidence(false, false, JsonNodeFactory.instance.arrayNode(), false, JsonNodeFactory.instance.arrayNode()); }
        public static Evidence purged() { return new Evidence(true, false, JsonNodeFactory.instance.arrayNode(), false, JsonNodeFactory.instance.arrayNode()); }
    }
}
