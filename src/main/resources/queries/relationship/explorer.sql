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
), latest_events AS MATERIALIZED (
    SELECT DISTINCT ON (ev.edge_id) ev.edge_id, ev.episode_no, ev.created_at, ev.id,
        ev.source_name, ev.target_name, ev.label, ev.summary, ev.direction, ev.status
    FROM relationship_graph_events ev
    WHERE ev.work_id = :workId AND ev.episode_no <= :cutoff
      AND ev.analysis_id = ANY(ARRAY(SELECT id FROM valid))
    ORDER BY ev.edge_id, ev.episode_no DESC, ev.created_at DESC, ev.id DESC
), visible AS MATERIALIZED (
    SELECT e.id, s.id AS source_id, t.id AS target_id, e.axis, e.kind, e.version,
        ev.label, coalesce(e.corrected_summary, ev.summary) AS summary, ev.direction, ev.status,
        ev.source_name, ev.target_name, ev.episode_no, ev.created_at, ev.id AS event_id
    FROM latest_events ev JOIN relationship_graph_edges e ON e.id = ev.edge_id AND e.work_id = :workId
    JOIN canonical s ON s.original_id = e.source_node_id
    JOIN canonical t ON t.original_id = e.target_node_id
    WHERE NOT e.suppressed AND s.id <> t.id
), endpoints AS (
    SELECT source_id AS id, target_id AS neighbor, source_name AS observed_name, episode_no, created_at, event_id FROM visible
    UNION ALL
    SELECT target_id, source_id, target_name, episode_no, created_at, event_id FROM visible
), degrees AS (
    SELECT id, count(DISTINCT neighbor) AS neighbor_count FROM endpoints GROUP BY id
), observed AS (
    SELECT DISTINCT ON (id) id, observed_name FROM endpoints ORDER BY id, episode_no DESC, created_at DESC, event_id DESC
), registered_profiles AS (
    SELECT character_id, count(*) AS count FROM work_nodes
    WHERE character_id IS NOT NULL AND merged_into_node_id IS NULL AND character_linked_episode_no <= :cutoff
    GROUP BY character_id
), people AS MATERIALIZED (
    SELECT n.id, n.character_id, d.neighbor_count,
        n.character_id IS NOT NULL AND n.character_linked_episode_no <= :cutoff AS registered,
        CASE WHEN n.character_linked_episode_no <= :cutoff AND c.status = 'ACTIVE'
             AND rp.count = 1
             THEN c.name ELSE o.observed_name END AS name
    FROM degrees d JOIN work_nodes n ON n.id = d.id JOIN observed o ON o.id = n.id
    LEFT JOIN characters c ON c.id = n.character_id AND c.work_id = :workId
    LEFT JOIN registered_profiles rp ON rp.character_id = n.character_id
)
