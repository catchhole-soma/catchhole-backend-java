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
