-- Retrieval chunks are derived from the current original, never from analysis chunks.
CREATE TABLE retrieval_sources (
 episode_id UUID PRIMARY KEY REFERENCES episodes(id) ON DELETE CASCADE,
 work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
 source_key VARCHAR(512) NOT NULL, source_version VARCHAR(100), source_hash VARCHAR(64) NOT NULL,
 chunk_policy VARCHAR(100) NOT NULL, chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
 prepared_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_retrieval_sources_work ON retrieval_sources(work_id);
CREATE TABLE retrieval_chunks (
 id UUID PRIMARY KEY,
 episode_id UUID NOT NULL REFERENCES retrieval_sources(episode_id) ON DELETE CASCADE,
 chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
 chunk_text TEXT NOT NULL, start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
 end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
 chunk_hash VARCHAR(64) NOT NULL, token_count INTEGER NOT NULL CHECK (token_count > 0),
 embedding VECTOR(1536), embedding_model VARCHAR(100), embedding_version VARCHAR(50),
 embedded_at TIMESTAMP,
 status VARCHAR(20) NOT NULL DEFAULT 'MISSING' CHECK (status IN ('MISSING','READY','FAILED')),
 failure_code VARCHAR(40), attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
 last_operation_id UUID REFERENCES chat_runs(id) ON DELETE SET NULL,
 CONSTRAINT uk_retrieval_chunk_index UNIQUE(episode_id,chunk_index),
 CONSTRAINT ck_retrieval_ready CHECK (status <> 'READY' OR
   (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_version IS NOT NULL AND embedded_at IS NOT NULL))
);
-- Small initial corpus: exact pgvector search avoids premature ANN tuning.
-- Consumers must additionally filter chunk_policy / embedding_model / embedding_version.
CREATE VIEW retrieval_current_chunks AS
SELECT c.*, s.work_id, s.source_key, s.source_version, s.source_hash, s.chunk_policy, e.episode_no
FROM retrieval_chunks c
JOIN retrieval_sources s ON s.episode_id=c.episode_id
JOIN episodes e ON e.id=s.episode_id AND e.work_id=s.work_id
JOIN works w ON w.id=s.work_id
JOIN members m ON m.id=w.member_id
WHERE w.lifecycle_status='ACTIVE' AND m.status='ACTIVE' AND e.status <> 'ARCHIVED'
  AND e.content_s3_key=s.source_key AND e.content_hash=s.source_hash
  AND e.content_s3_version IS NOT DISTINCT FROM s.source_version
  AND NOT EXISTS (SELECT 1 FROM episode_source_purge_requests p WHERE p.episode_id=e.id)
  AND c.status='READY' AND c.embedding IS NOT NULL;
