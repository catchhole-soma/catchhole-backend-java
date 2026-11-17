WITH latest AS MATERIALIZED (
    SELECT DISTINCT ON (a.episode_no)
        a.id, a.episode_id, a.episode_no, a.status, a.rejected_observation_count,
        e.title, coalesce(a.failures, '[]'::jsonb) <> '[]'::jsonb AS has_failures,
        (e.id IS NOT NULL AND e.status <> 'ARCHIVED' AND NOT a.evidence_purged
         AND NOT EXISTS (SELECT 1 FROM episode_source_purge_requests p WHERE p.episode_id = e.id)
         AND e.episode_no = a.episode_no AND e.content_hash IS NOT DISTINCT FROM a.source_hash
         AND j.id IS NOT NULL AND j.source_content_s3_key IS NOT DISTINCT FROM e.content_s3_key
         AND j.source_content_s3_version IS NOT DISTINCT FROM e.content_s3_version) AS source_available
    FROM relationship_graph_analyses a
    LEFT JOIN episodes e ON e.id = a.episode_id AND e.work_id = a.work_id
    LEFT JOIN analysis_jobs j ON j.id = a.id
    WHERE a.work_id = :workId AND a.generation = coalesce(
        (SELECT published_generation FROM relationship_backfill_works WHERE work_id = :workId), 0)
    ORDER BY a.episode_no, a.created_at DESC, a.id DESC
), valid AS MATERIALIZED (
    SELECT * FROM latest WHERE status = 'APPLIED'
        AND episode_no < coalesce((SELECT min(episode_no) FROM latest WHERE NOT source_available), 2147483647)
), work_nodes AS NOT MATERIALIZED (
    SELECT id, character_id, character_linked_episode_no, name, first_episode_no, merged_into_node_id
    FROM relationship_graph_nodes WHERE work_id = :workId
), canonical AS MATERIALIZED (
    -- Redirect depth is bounded to eight, matching NodeIndex. PK joins avoid recursive cardinality inflation.
    SELECT n.id AS original_id, coalesce(n8.id, n7.id, n6.id, n5.id, n4.id, n3.id, n2.id, n1.id, n.id) AS id
    FROM work_nodes n
    LEFT JOIN relationship_graph_nodes n1 ON n1.id = n.merged_into_node_id AND n1.work_id = :workId
    LEFT JOIN relationship_graph_nodes n2 ON n2.id = n1.merged_into_node_id AND n2.work_id = :workId
    LEFT JOIN relationship_graph_nodes n3 ON n3.id = n2.merged_into_node_id AND n3.work_id = :workId
    LEFT JOIN relationship_graph_nodes n4 ON n4.id = n3.merged_into_node_id AND n4.work_id = :workId
    LEFT JOIN relationship_graph_nodes n5 ON n5.id = n4.merged_into_node_id AND n5.work_id = :workId
    LEFT JOIN relationship_graph_nodes n6 ON n6.id = n5.merged_into_node_id AND n6.work_id = :workId
    LEFT JOIN relationship_graph_nodes n7 ON n7.id = n6.merged_into_node_id AND n7.work_id = :workId
    LEFT JOIN relationship_graph_nodes n8 ON n8.id = n7.merged_into_node_id AND n8.work_id = :workId
    WHERE n8.merged_into_node_id IS NULL
      AND (n.merged_into_node_id IS NULL OR n1.id IS NOT NULL)
      AND (n1.merged_into_node_id IS NULL OR n2.id IS NOT NULL)
      AND (n2.merged_into_node_id IS NULL OR n3.id IS NOT NULL)
      AND (n3.merged_into_node_id IS NULL OR n4.id IS NOT NULL)
      AND (n4.merged_into_node_id IS NULL OR n5.id IS NOT NULL)
      AND (n5.merged_into_node_id IS NULL OR n6.id IS NOT NULL)
      AND (n6.merged_into_node_id IS NULL OR n7.id IS NOT NULL)
      AND (n7.merged_into_node_id IS NULL OR n8.id IS NOT NULL)
), work_edges AS MATERIALIZED (
    -- User corrections, suppression and optimistic versions span all generations/cutoffs.
    SELECT e.id, e.source_node_id, e.target_node_id, e.axis, e.kind, e.canonical_key,
        e.version, e.suppressed, e.corrected_summary, e.created_at
    FROM relationship_graph_edges e WHERE e.work_id = :workId
), edge_directions AS MATERIALIZED (
    -- Direction is immutable in the stored canonical key, even for edges without an event.
    -- Keep malformed keys as NULL so stats() reports an integrity error rather than hiding them.
    SELECT e.*, d.direction
    FROM work_edges e LEFT JOIN LATERAL (
        SELECT candidate.direction
        FROM (VALUES ('DIRECTED'), ('MUTUAL'), ('UNDIRECTED')) candidate(direction)
        WHERE e.canonical_key = encode(sha256(convert_to(
            CASE WHEN candidate.direction = 'DIRECTED' OR e.source_node_id::text < e.target_node_id::text
                 THEN e.source_node_id::text || '|' || e.target_node_id::text
                 ELSE e.target_node_id::text || '|' || e.source_node_id::text END
            || '|' || e.axis || '|' || e.kind || '|' || candidate.direction, 'UTF8')), 'hex')
    ) d ON true
), first_events AS MATERIALIZED (
    SELECT edge_id, min(episode_no) AS first_episode_no
    FROM relationship_graph_events WHERE work_id = :workId GROUP BY edge_id
), slot_members AS MATERIALIZED (
    SELECT e.id AS edge_id, s.id AS source_id, t.id AS target_id, e.axis, e.kind, e.direction,
        e.version, e.suppressed, e.corrected_summary, e.created_at,
        e.source_node_id <> s.id OR e.target_node_id <> t.id AS moved,
        coalesce(f.first_episode_no, 2147483647) AS first_episode_no,
        CASE WHEN e.direction = 'DIRECTED' OR s.id::text < t.id::text
             THEN s.id::text || '|' || t.id::text ELSE t.id::text || '|' || s.id::text END
            || '|' || e.axis || '|' || e.kind || '|' || e.direction AS slot_key
    FROM edge_directions e JOIN canonical s ON s.original_id = e.source_node_id
    JOIN canonical t ON t.original_id = e.target_node_id
    LEFT JOIN first_events f ON f.edge_id = e.id
    WHERE e.direction IS NOT NULL AND s.id <> t.id
), ranked_members AS MATERIALIZED (
    SELECT m.*, row_number() OVER (PARTITION BY slot_key ORDER BY
        -- UUID ordering matches the lexical tie breaker used by Java and GH391.
        moved, first_episode_no, created_at NULLS LAST, edge_id) AS member_rank
    FROM slot_members m
), slot_metadata AS MATERIALIZED (
    SELECT slot_key, sum(version)::bigint AS version, bool_or(suppressed) AS suppressed,
        (array_agg(corrected_summary ORDER BY member_rank) FILTER (WHERE corrected_summary IS NOT NULL))[1]
            AS corrected_summary
    FROM ranked_members GROUP BY slot_key
), ranked_events AS MATERIALIZED (
    SELECT m.slot_key, m.source_id, m.target_id, ev.episode_no, ev.created_at, ev.id,
        ev.source_name, ev.target_name, ev.label, ev.summary, ev.direction, ev.status,
        row_number() OVER (PARTITION BY m.slot_key ORDER BY ev.episode_no DESC, m.member_rank,
            ev.created_at DESC, ev.id DESC) AS event_rank
    FROM relationship_graph_events ev JOIN ranked_members m ON m.edge_id = ev.edge_id
    WHERE ev.work_id = :workId AND ev.episode_no <= :cutoff
      AND ev.analysis_id = ANY(ARRAY(SELECT id FROM valid))
), visible AS MATERIALIZED (
    SELECT representative.edge_id AS id, representative.source_id, representative.target_id,
        representative.axis, representative.kind, metadata.version, ev.label,
        coalesce(metadata.corrected_summary, ev.summary) AS summary, ev.direction, ev.status,
        CASE WHEN ev.source_id = representative.source_id THEN ev.source_name ELSE ev.target_name END AS source_name,
        CASE WHEN ev.source_id = representative.source_id THEN ev.target_name ELSE ev.source_name END AS target_name,
        ev.episode_no, ev.created_at, ev.id AS event_id, metadata.slot_key
    FROM ranked_events ev JOIN slot_metadata metadata ON metadata.slot_key = ev.slot_key
    JOIN ranked_members representative ON representative.slot_key = ev.slot_key AND representative.member_rank = 1
    WHERE ev.event_rank = 1 AND NOT metadata.suppressed
), endpoints AS (
    SELECT source_id AS id, target_id AS neighbor, source_name AS observed_name, episode_no, created_at, event_id FROM visible
    UNION ALL
    SELECT target_id, source_id, target_name, episode_no, created_at, event_id FROM visible
), degrees AS (
    SELECT id, count(DISTINCT neighbor) AS neighbor_count, count(*) AS edge_count FROM endpoints GROUP BY id
), observed AS (
    SELECT DISTINCT ON (id) id, observed_name FROM endpoints ORDER BY id, episode_no DESC, created_at DESC, event_id DESC
), registered_profiles AS (
    SELECT character_id, count(*) AS count FROM work_nodes
    WHERE character_id IS NOT NULL AND merged_into_node_id IS NULL AND character_linked_episode_no <= :cutoff
    GROUP BY character_id
), people AS MATERIALIZED (
    SELECT n.id, n.character_id, d.neighbor_count, d.edge_count,
        n.character_id IS NOT NULL AND n.character_linked_episode_no <= :cutoff AS registered,
        CASE WHEN n.character_linked_episode_no <= :cutoff AND c.status = 'ACTIVE'
             AND rp.count = 1
             THEN c.name ELSE o.observed_name END AS name
    FROM degrees d JOIN work_nodes n ON n.id = d.id JOIN observed o ON o.id = n.id
    LEFT JOIN characters c ON c.id = n.character_id AND c.work_id = :workId
    LEFT JOIN registered_profiles rp ON rp.character_id = n.character_id
)
