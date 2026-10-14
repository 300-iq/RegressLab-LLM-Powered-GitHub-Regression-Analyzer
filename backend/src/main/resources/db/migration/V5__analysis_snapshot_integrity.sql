-- Provenance is part of the analysis input; retain older immutable snapshots.
ALTER TABLE comparison_analyses
    DROP CONSTRAINT comparison_analyses_comparison_id_analysis_version_policy_s_key;
ALTER TABLE comparison_analyses
    ADD CONSTRAINT analysis_snapshot_unique
        UNIQUE (comparison_id, analysis_version, policy_sha256, input_sha256);

-- SQL CHECK accepts UNKNOWN. Require these JSON fields to have non-null types;
-- the original constraints continue checking threshold bounds and classification.
ALTER TABLE comparison_analyses
    ADD CONSTRAINT analysis_threshold_number
        CHECK ((jsonb_typeof(policy->'practicalThresholdPercent') = 'number') IS TRUE),
    ADD CONSTRAINT analysis_classification_string
        CHECK ((jsonb_typeof(result->'classification') = 'string') IS TRUE);
