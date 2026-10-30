ALTER TABLE analysis_jobs ADD COLUMN relationship_service_paid boolean NOT NULL DEFAULT false;
ALTER TABLE analysis_jobs ADD COLUMN relationship_generation bigint NOT NULL DEFAULT 0;
ALTER TABLE relationship_graph_analyses ADD COLUMN generation bigint NOT NULL DEFAULT 0;
CREATE INDEX idx_relationship_graph_generation ON relationship_graph_analyses(work_id,generation,episode_no);
CREATE TABLE relationship_backfill_campaigns (id varchar(80) PRIMARY KEY, initialized boolean NOT NULL DEFAULT false);
INSERT INTO relationship_backfill_campaigns(id) VALUES ('relationship-backfill-20261030-v1');
CREATE TABLE relationship_backfill_works (
 work_id uuid PRIMARY KEY REFERENCES works(id) ON DELETE CASCADE,
 campaign_key varchar(80) NOT NULL REFERENCES relationship_backfill_campaigns(id),
 published_generation bigint NOT NULL DEFAULT 0, building_generation bigint NOT NULL DEFAULT 1,
 state varchar(20) NOT NULL, blocked_reason varchar(80), CHECK (state IN ('BUILDING','BLOCKED','COMPLETE'))
);
CREATE TABLE relationship_backfill_items (
 id uuid PRIMARY KEY,work_id uuid NOT NULL REFERENCES relationship_backfill_works(work_id) ON DELETE CASCADE,
 episode_id uuid NOT NULL REFERENCES episodes(id) ON DELETE CASCADE, episode_no integer NOT NULL,
 generation bigint NOT NULL,source_hash varchar(64) NOT NULL,source_key varchar(2048) NOT NULL,source_version varchar(255),
 service_paid boolean NOT NULL,job_id uuid REFERENCES analysis_jobs(id) ON DELETE SET NULL,
 state varchar(20) NOT NULL,reason varchar(80),lease_token uuid,
 CONSTRAINT uk_relationship_backfill_item UNIQUE(work_id,episode_id,generation,source_hash),
 CHECK(state IN ('WAITING','RUNNING','SUCCEEDED','FAILED','SKIPPED'))
);
CREATE INDEX idx_relationship_backfill_queue ON relationship_backfill_items(work_id,episode_no,state);
CREATE UNIQUE INDEX uk_relationship_backfill_running_campaign ON analysis_jobs((job_type)) WHERE job_type='RELATIONSHIP_EXTRACTION' AND status='RUNNING';
