ALTER TABLE relationship_backfill_items
    ADD COLUMN recovery_attempt_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_relationship_backfill_recovery_attempt_count
        CHECK (recovery_attempt_count BETWEEN 0 AND 1);
