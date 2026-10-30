package org.monitoring.catchholebackend.domain.episode.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class EpisodePurgeDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public void purgeNarrativeTimeEvidence(UUID episodeId) {
        // 후행 고정 문맥에 복사된 보류 인용까지 함께 파기한다. 실제 재분석을 자동 호출하지 않는다.
        jdbcTemplate.update("""
                update relationship_graph_events set evidence = null where analysis_id in (
                    select a.id from relationship_graph_analyses a where a.work_id in (select work_id from episodes where id = ?)
                    and a.episode_no >= (select min(episode_no) from relationship_graph_analyses where episode_id = ?))
                """, episodeId, episodeId);
        jdbcTemplate.update("""
                update relationship_graph_analyses set context = null, bindings = null, decisions = null,
                    evidence_purged = true, status = 'INVALIDATED'
                where work_id in (select work_id from episodes where id = ?)
                    and episode_no >= (select min(a.episode_no) from relationship_graph_analyses a where a.episode_id = ?)
                """, episodeId, episodeId);
        for (String table : java.util.List.of("setting_candidates", "character_facts")) {
            String source = table.equals("setting_candidates") ? "episode_id" : "source_episode_id";
            java.util.List<java.util.Map<String,Object>> rows = jdbcTemplate.queryForList("select id, narrative_time from " + table + " where " + source + " = ? and narrative_time is not null", episodeId);
            for (var row : rows) {
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var time = mapper.readTree(row.get("narrative_time").toString());
                    var redacted = org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.purgeEvidence(time);
                    jdbcTemplate.update("update " + table + " set narrative_time = cast(? as jsonb) where id = ?", redacted.toString(), row.get("id"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
            }
        }
        var rows = jdbcTemplate.queryForList("select id, current_narrative_time from characters where current_narrative_time is not null and work_id in (select work_id from episodes where id = ?)", episodeId);
        for (var row : rows) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var time = mapper.readTree(row.get("current_narrative_time").toString());
                String id = time.path("sourceCandidateId").asText();
                Integer count = jdbcTemplate.queryForObject("select count(*) from setting_candidates where id = ? and episode_id = ?", Integer.class, UUID.fromString(id), episodeId);
                if (count != null && count > 0) jdbcTemplate.update("update characters set current_narrative_time = cast(? as jsonb) where id = ?",
                        org.monitoring.catchholebackend.domain.character.processor.CharacterNarrativeTime.purgeEvidence(time).toString(), row.get("id"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
        }
    }

    public int deleteChunks(UUID episodeId) {
        int deleted = jdbcTemplate.update("delete from retrieval_chunks where episode_id = ?", episodeId);
        deleted += jdbcTemplate.update("delete from retrieval_sources where episode_id = ?", episodeId);
        return deleted + jdbcTemplate.update("delete from episode_chunks where episode_id = ?", episodeId);
    }
}
