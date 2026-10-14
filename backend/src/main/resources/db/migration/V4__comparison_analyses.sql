-- Append-only conditional analyses; existing descriptive results remain immutable.
CREATE TABLE comparison_analyses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    comparison_id uuid NOT NULL REFERENCES run_comparisons(id),
    analysis_version varchar(50) NOT NULL,
    policy_sha256 char(64) NOT NULL CHECK (policy_sha256 ~ '^[0-9a-f]{64}$'),
    input_sha256 char(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    policy jsonb NOT NULL CHECK (jsonb_typeof(policy) = 'object'),
    result jsonb NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    data_origin varchar(4) NOT NULL CHECK (data_origin IN ('REAL', 'DEMO')),
    provenance varchar(20) NOT NULL CHECK (provenance IN ('LOCAL_UNVERIFIED', 'GITHUB_METADATA')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (comparison_id, analysis_version, policy_sha256),
    CHECK (result ? 'classification' AND result->>'classification' IN
        ('REGRESSION_INDICATED', 'IMPROVEMENT_INDICATED', 'NO_MEANINGFUL_CHANGE_DETECTED', 'INCONCLUSIVE', 'INVALID_COMPARISON')),
    CHECK ((policy->>'practicalThresholdPercent')::numeric BETWEEN 0.1 AND 50.0)
);
