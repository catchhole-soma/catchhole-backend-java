ALTER TABLE setting_candidates
    DROP CONSTRAINT ck_setting_candidates_suggested_operation;

ALTER TABLE setting_candidates
    ADD CONSTRAINT ck_setting_candidates_suggested_operation
        CHECK (suggested_operation IS NULL OR suggested_operation IN (
            'ADD', 'UPDATE', 'MERGE', 'REMOVE', 'HISTORY_ONLY', 'EXCLUDE', 'REVIEW_REQUIRED'
        ));
