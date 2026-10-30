ALTER TABLE analysis_jobs ADD COLUMN relationship_analysis_enabled boolean NOT NULL DEFAULT false;
CREATE TABLE relationship_graph_analyses (
    id uuid PRIMARY KEY REFERENCES analysis_jobs(id) ON DELETE CASCADE,
    work_id uuid NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    episode_id uuid NOT NULL REFERENCES episodes(id), episode_no integer NOT NULL CHECK (episode_no > 0),
    source_hash varchar(64) NOT NULL, request_hash varchar(64), context_token varchar(64), completion_hash varchar(64),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING','PREPARED','COMPLETED','FAILED','APPLIED','INVALIDATED')),
    context jsonb, bindings jsonb, decisions jsonb, rejected_observation_count integer NOT NULL DEFAULT 0,
    failure_code varchar(40), evidence_purged boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL, updated_at timestamp NOT NULL
);
CREATE INDEX idx_relationship_graph_analysis_episode ON relationship_graph_analyses(work_id,episode_no,created_at);
CREATE TABLE relationship_graph_nodes (
    id uuid PRIMARY KEY, work_id uuid NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    character_id uuid REFERENCES characters(id) ON DELETE SET NULL, character_linked_episode_no integer, name varchar(100) NOT NULL, first_episode_no integer NOT NULL,
    created_at timestamp NOT NULL, updated_at timestamp NOT NULL
);
CREATE INDEX idx_relationship_graph_node_work ON relationship_graph_nodes(work_id);
CREATE TABLE relationship_graph_edges (
    id uuid PRIMARY KEY, work_id uuid NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    source_node_id uuid NOT NULL REFERENCES relationship_graph_nodes(id),
    target_node_id uuid NOT NULL REFERENCES relationship_graph_nodes(id),
    axis varchar(20) NOT NULL, kind varchar(30) NOT NULL, canonical_key varchar(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0, suppressed boolean NOT NULL DEFAULT false, corrected_summary varchar(500),
    created_at timestamp NOT NULL, updated_at timestamp NOT NULL,
    CONSTRAINT uk_relationship_graph_edge_key UNIQUE(work_id,canonical_key),
    CONSTRAINT ck_relationship_graph_no_self CHECK(source_node_id<>target_node_id)
);
CREATE TABLE relationship_graph_events (
    id uuid PRIMARY KEY, work_id uuid NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    analysis_id uuid NOT NULL REFERENCES relationship_graph_analyses(id) ON DELETE CASCADE,
    edge_id uuid NOT NULL REFERENCES relationship_graph_edges(id),
    episode_id uuid NOT NULL REFERENCES episodes(id), episode_no integer NOT NULL,
    source_name varchar(100) NOT NULL, target_name varchar(100) NOT NULL, label varchar(60) NOT NULL,
    summary varchar(500) NOT NULL, direction varchar(20) NOT NULL, status varchar(20) NOT NULL,
    evidence jsonb, created_at timestamp NOT NULL, updated_at timestamp NOT NULL,
    CONSTRAINT uk_relationship_graph_event UNIQUE(analysis_id,edge_id)
);
CREATE INDEX idx_relationship_graph_event_cutoff ON relationship_graph_events(work_id,episode_no);
