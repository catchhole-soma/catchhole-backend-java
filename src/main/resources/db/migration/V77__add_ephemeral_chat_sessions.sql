CREATE TABLE chat_sessions (
 id UUID PRIMARY KEY,
 work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
 creation_key UUID NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMPTZ NOT NULL,
 closed_at TIMESTAMPTZ,
 next_sequence BIGINT NOT NULL DEFAULT 1 CHECK(next_sequence BETWEEN 1 AND 201),
 UNIQUE(work_id, creation_key)
);
CREATE INDEX idx_chat_sessions_active ON chat_sessions(work_id,created_at,id) WHERE closed_at IS NULL;
CREATE INDEX idx_chat_sessions_expiry ON chat_sessions(expires_at) WHERE closed_at IS NULL;
ALTER TABLE chat_questions ADD COLUMN session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE;
ALTER TABLE chat_questions ADD COLUMN sequence_no BIGINT;
ALTER TABLE chat_questions ADD COLUMN submitted_at TIMESTAMPTZ;
ALTER TABLE chat_questions ADD CONSTRAINT ck_chat_question_session CHECK (
 (session_id IS NULL AND sequence_no IS NULL AND submitted_at IS NULL) OR
 (session_id IS NOT NULL AND sequence_no > 0 AND submitted_at IS NOT NULL));
CREATE UNIQUE INDEX uk_chat_question_sequence ON chat_questions(session_id,sequence_no);
-- 종료 시 row를 연쇄 삭제하지 않는다. 본문 삭제와 늦은 비용 정산을 분리한다.
