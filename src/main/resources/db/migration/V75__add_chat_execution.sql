CREATE TABLE chat_questions (
 id UUID PRIMARY KEY, member_id BIGINT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
 work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
 idempotency_key VARCHAR(100) NOT NULL, input_hash VARCHAR(64) NOT NULL,
 question VARCHAR(4000), episode_from INTEGER, episode_to INTEGER,
 generation INTEGER NOT NULL, current_attempt_id UUID NOT NULL,
 CONSTRAINT uk_chat_question_key UNIQUE(member_id,work_id,idempotency_key)
);
CREATE TABLE chat_runs (
 id UUID PRIMARY KEY, question_id UUID REFERENCES chat_questions(id) ON DELETE CASCADE,
 work_id UUID NOT NULL REFERENCES works(id) ON DELETE CASCADE,
 generation INTEGER NOT NULL, state VARCHAR(30) NOT NULL,
 deadline TIMESTAMP WITH TIME ZONE NOT NULL, usage_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
 runner_id UUID, actor VARCHAR(100), policy VARCHAR(100), token_budget BIGINT NOT NULL,
 allocated_tokens BIGINT NOT NULL, call_count INTEGER NOT NULL, tool_count INTEGER NOT NULL,
 result_json TEXT, receipt_json TEXT, finish_hash VARCHAR(64), failure VARCHAR(40),
 CONSTRAINT uk_chat_run_generation UNIQUE(question_id,generation),
 CONSTRAINT ck_chat_run_owner CHECK ((question_id IS NOT NULL AND actor IS NULL AND policy IS NULL) OR (question_id IS NULL AND actor IS NOT NULL AND policy IS NOT NULL)),
 CONSTRAINT ck_chat_run_budget CHECK (token_budget>0 AND allocated_tokens>=0 AND allocated_tokens<=token_budget)
);
CREATE INDEX idx_chat_runs_state_deadline ON chat_runs(state,deadline);
CREATE INDEX idx_chat_runs_work ON chat_runs(work_id);
ALTER TABLE ai_token_usages ALTER COLUMN analysis_job_id DROP NOT NULL;
ALTER TABLE ai_token_usages ADD COLUMN chat_run_id UUID REFERENCES chat_runs(id) ON DELETE CASCADE;
ALTER TABLE ai_token_usages ADD CONSTRAINT ck_ai_token_usage_owner CHECK ((analysis_job_id IS NOT NULL)::integer + (chat_run_id IS NOT NULL)::integer = 1);
CREATE INDEX idx_ai_token_usages_chat_run ON ai_token_usages(chat_run_id,status);
