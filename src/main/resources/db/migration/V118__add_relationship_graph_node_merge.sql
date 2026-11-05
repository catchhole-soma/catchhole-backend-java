-- GH382: a node recorded under an alias redirects to the single node of its confirmed profile.
-- The merged row keeps its id/name because frozen bindings, events and edges still point at it;
-- reads resolve it to the profile node. Physical edge/event consolidation is a separate cleanup.
-- The self-reference stays NO ACTION so the work purge's single DELETE remains valid.
ALTER TABLE relationship_graph_nodes
    ADD COLUMN merged_into_node_id uuid REFERENCES relationship_graph_nodes(id),
    ADD COLUMN merged_at_episode_no integer,
    ADD CONSTRAINT ck_relationship_graph_node_merge_self
        CHECK (merged_into_node_id IS NULL OR merged_into_node_id <> id),
    ADD CONSTRAINT ck_relationship_graph_node_merge_unlinked
        CHECK (merged_into_node_id IS NULL OR character_id IS NULL);

-- Profile-node lookup during finalize/cleanup. A unique (work_id, character_id) index waits for
-- the one-time cleanup because already split production rows would violate it.
CREATE INDEX idx_relationship_graph_node_profile
    ON relationship_graph_nodes(work_id, character_id) WHERE character_id IS NOT NULL;
