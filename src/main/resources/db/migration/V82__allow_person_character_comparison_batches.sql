-- New batches compare every fact type for one person. Existing typed batches retain their scope.
ALTER TABLE character_fact_comparison_batches
    ALTER COLUMN canonical_fact_type DROP NOT NULL;
