-- 접수 때 색인 채우기(2026-11-02): 설정집도 회차처럼 RAG 색인(조각 + 임베딩)에 올린다.
-- 회차 표(V76 retrieval_sources/retrieval_chunks)와 같은 모양이며 설정집 revision 하나가 출처 하나다.
-- 검수 Worker가 접수 시점의 현재 판(보관되지 않은 설정집의 current revision)만 채운다. 분석용 setting_book_chunks와 별개다.
CREATE TABLE retrieval_setting_book_sources (
 revision_id UUID PRIMARY KEY REFERENCES setting_book_revisions(id) ON DELETE CASCADE,
 setting_book_id UUID NOT NULL REFERENCES upload_files(id) ON DELETE CASCADE,
 work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
 source_key VARCHAR(512) NOT NULL, source_version VARCHAR(255), source_hash VARCHAR(64) NOT NULL,
 chunk_policy VARCHAR(100) NOT NULL, chunk_count INTEGER NOT NULL CHECK (chunk_count >= 0),
 prepared_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_retrieval_setting_book_sources_work ON retrieval_setting_book_sources(work_id);
CREATE INDEX idx_retrieval_setting_book_sources_book ON retrieval_setting_book_sources(setting_book_id);
CREATE TABLE retrieval_setting_book_chunks (
 id UUID PRIMARY KEY,
 revision_id UUID NOT NULL REFERENCES retrieval_setting_book_sources(revision_id) ON DELETE CASCADE,
 chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
 chunk_text TEXT NOT NULL, start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
 end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
 chunk_hash VARCHAR(64) NOT NULL, token_count INTEGER NOT NULL CHECK (token_count > 0),
 embedding VECTOR(1536), embedding_model VARCHAR(100), embedding_version VARCHAR(50),
 embedded_at TIMESTAMP,
 status VARCHAR(20) NOT NULL DEFAULT 'MISSING' CHECK (status IN ('MISSING','READY','FAILED')),
 failure_code VARCHAR(40), attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
 CONSTRAINT uk_retrieval_setting_book_chunk_index UNIQUE(revision_id,chunk_index),
 CONSTRAINT ck_retrieval_setting_book_ready CHECK (status <> 'READY' OR
   (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_version IS NOT NULL AND embedded_at IS NOT NULL))
);
COMMENT ON TABLE retrieval_setting_book_sources IS '검수 접수 때 채우는 설정집 RAG 색인 header. 현재 원문 key/version/hash와 조각 정책을 보존한다. 설정집 수정·보관 시 Spring이 그 설정집의 행을 지운다.';
COMMENT ON TABLE retrieval_setting_book_chunks IS '설정집 RAG 조각(원문 slice, Unicode code point offset)과 임베딩. 소비자는 policy/model/version과 고정 검수 범위를 함께 필터한다.';
