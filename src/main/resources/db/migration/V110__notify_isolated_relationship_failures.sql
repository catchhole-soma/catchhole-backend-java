-- A relationship failure in an ordinary extraction does not fail its owning Job.
-- Dedicated relationship Jobs already emit an ANALYSIS_JOB failure notification.
ALTER TABLE relationship_graph_analyses ADD COLUMN failure_diagnostics jsonb;

CREATE FUNCTION record_relationship_partial_failure_notification() RETURNS trigger AS $$
DECLARE
    owning_job analysis_jobs%ROWTYPE;
    diagnostic jsonb;
    code text;
BEGIN
    IF NEW.status <> 'FAILED' THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'FAILED' THEN RETURN NEW; END IF;
    SELECT * INTO owning_job FROM analysis_jobs
        WHERE id = NEW.id AND work_id = NEW.work_id AND episode_id = NEW.episode_id
            AND source_kind = 'EPISODE' AND job_type = 'SETTING_EXTRACTION'
            AND status = 'RUNNING' AND relationship_analysis_enabled;
    IF NOT FOUND THEN RETURN NEW; END IF;

    diagnostic := safe_analysis_failure_notification_diagnostics(NEW.failure_diagnostics);
    IF diagnostic IS NULL THEN
        diagnostic := jsonb_build_object('schemaVersion', 1, 'origin', 'SPRING',
            'stage', CASE
                WHEN NEW.failure_code = 'EXTRACTION_FAILED' THEN 'RELATIONSHIP_EXTRACTION'
                WHEN NEW.failure_code = 'COMPARISON_FAILED' THEN 'RELATIONSHIP_COMPARE'
                WHEN NEW.failure_code = 'INPUT_TOO_LARGE' THEN
                    CASE WHEN NEW.context_token IS NULL THEN 'RELATIONSHIP_EXTRACTION' ELSE 'RELATIONSHIP_COMPARE' END
                ELSE 'UNKNOWN' END,
            'reasonCode', CASE WHEN NEW.failure_code = 'INPUT_TOO_LARGE' THEN 'RELATIONSHIP_INPUT_TOO_LARGE' ELSE 'UNKNOWN' END,
            'sourceErrorCode', 'UNKNOWN');
    END IF;
    code := CASE
        WHEN diagnostic->>'reasonCode' = 'LLM_OUTPUT_TRUNCATED' THEN 'LLM_OUTPUT_TRUNCATED'
        WHEN diagnostic->>'reasonCode' = 'LLM_TIMEOUT' THEN 'LLM_NETWORK_ERROR'
        WHEN diagnostic->>'reasonCode' IN ('LLM_PROVIDER_ERROR', 'LLM_RATE_LIMIT') THEN 'LLM_PROVIDER_ERROR'
        WHEN diagnostic->>'reasonCode' LIKE 'RELATIONSHIP_%'
            OR diagnostic->>'reasonCode' = 'COMPARISON_RESULT_INVALID' THEN 'COMPARISON_VALIDATION_FAILED'
        ELSE 'UNEXPECTED_ERROR' END;
    INSERT INTO analysis_failure_notifications
        (work_id, analysis_job_id, episode_id, episode_no, failure_kind, failure_code, source_transaction,
         failure_diagnostics, execution_job_id, source_analysis_job_id, job_type, target_episode_nos,
         diagnostic_group_key, execution_attempt_id)
    VALUES (NEW.work_id, NEW.id, NEW.episode_id, NEW.episode_no, 'RELATIONSHIP_PARTIAL', code, txid_current(),
        diagnostic, NEW.id, NEW.id, owning_job.job_type, ARRAY[NEW.episode_no],
        md5(jsonb_build_object('attempt', owning_job.failure_notification_attempt_id, 'diagnostics', diagnostic)::text),
        owning_job.failure_notification_attempt_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER relationship_partial_failure_notification
    AFTER INSERT OR UPDATE OF status ON relationship_graph_analyses
    FOR EACH ROW EXECUTE FUNCTION record_relationship_partial_failure_notification();

-- Keep the first failure's diagnostics immutable; clear them before a new attempt.
CREATE FUNCTION reset_relationship_failure_diagnostics() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'FAILED' AND NEW.status <> 'FAILED' THEN
        NEW.failure_diagnostics := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER relationship_failure_diagnostics_reset BEFORE UPDATE OF status ON relationship_graph_analyses
    FOR EACH ROW EXECUTE FUNCTION reset_relationship_failure_diagnostics();

-- Include the new durable copy in the existing source-purge transaction.
CREATE OR REPLACE FUNCTION purge_analysis_failure_excerpts() RETURNS trigger AS $$
BEGIN
    UPDATE analysis_jobs SET failure_diagnostics = failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND failure_diagnostics ? 'excerpts';
    UPDATE setting_candidates SET comparison_failure_diagnostics = comparison_failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND comparison_failure_diagnostics ? 'excerpts';
    UPDATE world_setting_candidates SET comparison_failure_diagnostics = comparison_failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND comparison_failure_diagnostics ? 'excerpts';
    UPDATE character_fact_comparison_batches SET failure_diagnostics = failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND failure_diagnostics ? 'excerpts';
    UPDATE world_setting_comparison_batches SET failure_diagnostics = failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND failure_diagnostics ? 'excerpts';
    UPDATE relationship_graph_analyses SET failure_diagnostics = failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND failure_diagnostics ? 'excerpts';
    UPDATE analysis_failure_notifications SET failure_diagnostics = failure_diagnostics - 'excerpts'
        WHERE work_id = OLD.work_id AND failure_diagnostics ? 'excerpts';
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
