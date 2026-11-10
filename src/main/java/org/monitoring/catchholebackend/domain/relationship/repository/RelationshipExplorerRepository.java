package org.monitoring.catchholebackend.domain.relationship.repository;

import static org.monitoring.catchholebackend.domain.relationship.dto.RelationshipGraphContract.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL read model: no managed analysis/event entities or complete analysis JSON. */
@Repository
public class RelationshipExplorerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final String base;

    public RelationshipExplorerRepository(NamedParameterJdbcTemplate jdbc) throws IOException {
        this.jdbc = jdbc;
        this.base = new ClassPathResource("queries/relationship/explorer.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public record Analysis(UUID id, EpisodeRef episode, String status, boolean valid,
            int rejected, boolean failures, boolean openHold) {}
    public record Stats(long edgeCount, long peopleCount, boolean integrity) {}
    public record Person(Node node, long neighborCount) {}
    public record Row(UUID id, UUID source, UUID target, Axis axis, Kind kind, String label,
            String summary, Direction direction, EdgeStatus status, long version) {}
    public record FailedPair(UUID source, UUID target, int episodeNo, boolean valid) {}

    private MapSqlParameterSource params(UUID workId, int cutoff) {
        return new MapSqlParameterSource("workId", workId).addValue("cutoff", cutoff);
    }

    public List<Analysis> analyses(UUID workId, int cutoff) {
        return jdbc.query(base + """
            , consumed AS (
                SELECT analysis_id::text AS analysis_id, observation_ref AS ref
                FROM relationship_hold_closures WHERE work_id = :workId
                UNION
                SELECT a.bindings->'heldSources'->r.ref->>'analysisId',
                       a.bindings->'heldSources'->r.ref->>'observationRef'
                FROM valid v JOIN relationship_graph_analyses a ON a.id = v.id
                CROSS JOIN LATERAL jsonb_array_elements(coalesce(a.decisions, '[]')) d
                CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(d->'observationRefs', '[]')) r(ref)
                WHERE v.episode_no <= :cutoff AND d->>'operation' <> 'HOLD' AND r.ref LIKE 'H%'
            )
            SELECT l.*, v.id IS NOT NULL AS valid,
                EXISTS (
                    SELECT 1 FROM relationship_graph_analyses a
                    CROSS JOIN LATERAL jsonb_array_elements(coalesce(a.decisions, '[]')) d
                    CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(d->'observationRefs', '[]')) r(ref)
                    WHERE a.id = l.id AND d->>'operation' = 'HOLD' AND r.ref LIKE 'O%'
                    AND NOT EXISTS (SELECT 1 FROM consumed c WHERE c.analysis_id = a.id::text AND c.ref = r.ref)
                ) AS open_hold
            FROM latest l LEFT JOIN valid v ON v.id = l.id ORDER BY l.episode_no
            """, params(workId, cutoff), (r, i) -> new Analysis(uuid(r, "id"),
                new EpisodeRef(uuid(r, "episode_id"), r.getInt("episode_no"), r.getString("title")),
                r.getString("status"), r.getBoolean("valid"), r.getInt("rejected_observation_count"),
                r.getBoolean("has_failures"), r.getBoolean("open_hold")));
    }

    public Stats stats(UUID workId, int cutoff) {
        return jdbc.queryForObject(base + """
            SELECT (SELECT count(*) FROM visible) AS edges, (SELECT count(*) FROM people) AS people,
                (SELECT count(*) FROM work_nodes) = (SELECT count(*) FROM canonical) AS integrity
            """, params(workId, cutoff), (r, i) -> new Stats(r.getLong("edges"), r.getLong("people"), r.getBoolean("integrity")));
    }

    public List<Person> people(UUID workId, int cutoff, String query, int offset, int limit) {
        // position() treats % and _ literally; no user-controlled SQL or wildcard expansion.
        String matching = query.isEmpty() ? "" : """
            , matching AS MATERIALIZED (
                SELECT p.id FROM people p WHERE position(:query IN lower(p.name)) > 0
                UNION
                SELECT c.id FROM canonical c JOIN work_nodes n ON n.id = c.original_id
                WHERE n.first_episode_no <= :cutoff AND position(:query IN lower(n.name)) > 0
                UNION
                SELECT p.id FROM setting_candidates a JOIN episodes e ON e.id = a.episode_id
                JOIN analysis_jobs aj ON aj.id = a.analysis_job_id AND aj.source_content_hash = e.content_hash
                    AND aj.source_content_s3_key = e.content_s3_key
                    AND aj.source_content_s3_version IS NOT DISTINCT FROM e.content_s3_version
                JOIN characters ch ON ch.id = a.matched_character_id AND ch.work_id = :workId AND ch.status = 'ACTIVE'
                JOIN people p ON p.character_id = a.matched_character_id AND p.registered
                WHERE a.work_id = :workId AND a.candidate_kind = 'CHARACTER_DISCOVERY' AND a.review_status = 'CONFIRMED'
                    AND e.episode_no <= :cutoff AND e.status <> 'ARCHIVED'
                    AND NOT EXISTS (SELECT 1 FROM episode_source_purge_requests purge WHERE purge.episode_id = e.id)
                    AND position(:query IN lower(a.entity_name)) > 0
            )
            """;
        return jdbc.query(base + matching + "SELECT p.* FROM people p "
                + (query.isEmpty() ? "" : "JOIN matching m ON m.id = p.id ")
                + "ORDER BY neighbor_count DESC, name, p.id LIMIT :limit OFFSET :offset",
                params(workId, cutoff).addValue("query", query.toLowerCase(Locale.ROOT))
                    .addValue("offset", offset).addValue("limit", limit), (r, i) -> person(r));
    }

    public List<Person> nodes(UUID workId, int cutoff, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(base + "SELECT * FROM people WHERE id IN (:ids) ORDER BY id",
                params(workId, cutoff).addValue("ids", ids), (r, i) -> person(r));
    }

    public long scopeCount(UUID workId, int cutoff, UUID focus) {
        return jdbc.queryForObject(base + "SELECT count(*) FROM visible WHERE source_id = :focus OR target_id = :focus",
                params(workId, cutoff).addValue("focus", focus), Long.class);
    }

    public List<Row> edges(UUID workId, int cutoff, UUID focus, UUID edge, int offset, int limit) {
        String filter = edge != null ? "WHERE id = :edge" : focus != null ? "WHERE source_id = :focus OR target_id = :focus" : "";
        return jdbc.query(base + "SELECT * FROM visible " + filter + " ORDER BY episode_no DESC, id LIMIT :limit OFFSET :offset",
                params(workId, cutoff).addValue("focus", focus).addValue("edge", edge)
                    .addValue("limit", limit).addValue("offset", offset), (r, i) -> new Row(uuid(r, "id"),
                        uuid(r, "source_id"), uuid(r, "target_id"), Axis.valueOf(r.getString("axis")),
                        Kind.valueOf(r.getString("kind")), r.getString("label"), r.getString("summary"),
                        Direction.valueOf(r.getString("direction")), EdgeStatus.valueOf(r.getString("status")), r.getLong("version")));
    }

    private static final String EVIDENCE = """
        , quotes AS (
            SELECT DISTINCT ON (ev.edge_id, q->>'episodeId', q->>'startOffset', q->>'endOffset')
                ev.edge_id, e.episode_id, e.episode_no, e.title, q->>'quote' AS quote,
                (q->>'startOffset')::integer AS start_offset, (q->>'endOffset')::integer AS end_offset
            FROM relationship_graph_events ev JOIN valid a ON a.id = ev.analysis_id
            CROSS JOIN LATERAL jsonb_array_elements(coalesce(ev.evidence, '[]')) q
            JOIN valid e ON e.episode_id::text = q->>'episodeId' AND e.episode_no::text = q->>'episodeNo'
            WHERE ev.work_id = :workId AND ev.edge_id IN (:ids) AND ev.episode_no <= :cutoff
                AND e.episode_no <= :cutoff AND length(trim(coalesce(q->>'quote', ''))) > 0
            ORDER BY ev.edge_id, q->>'episodeId', q->>'startOffset', q->>'endOffset', ev.episode_no, ev.created_at, ev.id
        )
        """;

    public Map<UUID, Integer> evidenceCounts(UUID workId, int cutoff, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Integer> result = new HashMap<>();
        jdbc.query(base + EVIDENCE + "SELECT edge_id, count(*) AS count FROM quotes GROUP BY edge_id",
                params(workId, cutoff).addValue("ids", ids), (org.springframework.jdbc.core.RowCallbackHandler)
                r -> result.put(uuid(r, "edge_id"), r.getInt("count")));
        return result;
    }

    public List<PublicEvidence> evidence(UUID workId, int cutoff, UUID edge) {
        return jdbc.query(base + EVIDENCE + "SELECT * FROM quotes ORDER BY episode_no, start_offset, end_offset",
                params(workId, cutoff).addValue("ids", List.of(edge)), (r, i) -> new PublicEvidence(
                    uuid(r, "episode_id"), r.getInt("episode_no"), r.getString("title"), r.getString("quote"),
                    r.getInt("start_offset"), r.getInt("end_offset")));
    }

    public List<FailedPair> failures(UUID workId, int cutoff) {
        return jdbc.query(base + """
            SELECT a.episode_no, s.id AS source, t.id AS target,
                jsonb_array_length(coalesce(o->'sourceNodeRefs', '[]')) = 1
                AND jsonb_array_length(coalesce(o->'targetNodeRefs', '[]')) = 1
                AND o->'sourceNodeRefs'->>0 <> o->'targetNodeRefs'->>0
                AND s.id IS NOT NULL AND t.id IS NOT NULL AS valid_pair
            FROM valid v JOIN relationship_graph_analyses a ON a.id = v.id
            CROSS JOIN LATERAL jsonb_array_elements(coalesce(a.failures, '[]')) f
            CROSS JOIN LATERAL jsonb_array_elements_text(coalesce(f->'observationRefs', '[]')) ref(value)
            LEFT JOIN LATERAL jsonb_array_elements(coalesce(a.context->'observations', '[]')) o ON o->>'ref' = ref.value
            LEFT JOIN canonical s ON s.original_id::text = a.bindings->'nodes'->(o->'sourceNodeRefs'->>0)->>'id'
            LEFT JOIN canonical t ON t.original_id::text = a.bindings->'nodes'->(o->'targetNodeRefs'->>0)->>'id'
            WHERE v.episode_no <= :cutoff AND v.has_failures
            """, params(workId, cutoff), (r, i) -> new FailedPair(uuid(r, "source"), uuid(r, "target"),
                    r.getInt("episode_no"), r.getBoolean("valid_pair")));
    }

    private static Person person(ResultSet r) throws SQLException {
        return new Person(new Node(uuid(r, "id"), r.getString("name"), null, r.getBoolean("registered")), r.getLong("neighbor_count"));
    }
    private static UUID uuid(ResultSet r, String name) throws SQLException { return r.getObject(name, UUID.class); }
}
