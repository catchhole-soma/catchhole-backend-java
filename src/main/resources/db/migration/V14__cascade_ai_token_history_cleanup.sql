ALTER TABLE ai_token_accounts
    DROP CONSTRAINT fk_ai_token_accounts_member,
    ADD CONSTRAINT fk_ai_token_accounts_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE;

ALTER TABLE ai_token_grants
    DROP CONSTRAINT fk_ai_token_grants_member,
    ADD CONSTRAINT fk_ai_token_grants_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE;

ALTER TABLE ai_token_usages
    DROP CONSTRAINT fk_ai_token_usages_member,
    ADD CONSTRAINT fk_ai_token_usages_member
        FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE,
    DROP CONSTRAINT fk_ai_token_usages_work,
    ADD CONSTRAINT fk_ai_token_usages_work
        FOREIGN KEY (work_id) REFERENCES works (id) ON DELETE CASCADE,
    DROP CONSTRAINT fk_ai_token_usages_analysis_job,
    ADD CONSTRAINT fk_ai_token_usages_analysis_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_jobs (id) ON DELETE CASCADE;
