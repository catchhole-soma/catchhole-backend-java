-- Immutable hash-only identity for actual validation query embedding calls.
ALTER TABLE ai_token_usages ADD COLUMN validation_call_input JSONB;
ALTER TABLE ai_token_usages ADD CONSTRAINT ck_validation_call_input_owner
    CHECK (validation_call_input IS NULL OR validation_job_id IS NOT NULL);
COMMENT ON COLUMN ai_token_usages.validation_call_input IS 'Versioned input/query hashes and retrieval model/version/dimensions fixed before the provider call; no manuscript or query text.';
