-- A closure is an explicit continuation acknowledgement, not a semantic rejection.
-- Original graph decisions/context/evidence remain in their existing lifetime-managed tables.
CREATE TABLE relationship_hold_closures (
    id uuid PRIMARY KEY,
    work_id uuid NOT NULL REFERENCES works(id) ON DELETE CASCADE,
    analysis_id uuid NOT NULL REFERENCES relationship_graph_analyses(id) ON DELETE CASCADE,
    observation_ref varchar(40) NOT NULL CHECK (observation_ref ~ '^O[1-9][0-9]*$'),
    continuation_request_id uuid NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_relationship_hold_closure UNIQUE (analysis_id, observation_ref)
);
CREATE INDEX idx_relationship_hold_closures_work ON relationship_hold_closures(work_id);
