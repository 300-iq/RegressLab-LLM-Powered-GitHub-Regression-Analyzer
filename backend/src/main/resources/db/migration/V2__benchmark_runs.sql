-- V1 remains intact for audit. New writes use normalized run-oriented tables.
ALTER TABLE repositories ADD COLUMN owner varchar(39) GENERATED ALWAYS AS (split_part(full_name, '/', 1)) STORED;
ALTER TABLE repositories ADD COLUMN name varchar(100) GENERATED ALWAYS AS (split_part(full_name, '/', 2)) STORED;
ALTER TABLE repositories ADD COLUMN github_url text GENERATED ALWAYS AS ('https://github.com/' || full_name) STORED;
ALTER TABLE repositories ADD COLUMN data_origin varchar(4) NOT NULL DEFAULT 'REAL' CHECK (data_origin IN ('REAL', 'DEMO'));
ALTER TABLE repositories ADD COLUMN provenance varchar(20) NOT NULL DEFAULT 'LOCAL_UNVERIFIED' CHECK (provenance = 'LOCAL_UNVERIFIED');
ALTER TABLE pull_requests ADD COLUMN baseline_sha char(40) CHECK (baseline_sha ~ '^[0-9a-f]{40}$');
ALTER TABLE pull_requests ADD COLUMN candidate_sha char(40) CHECK (candidate_sha ~ '^[0-9a-f]{40}$');
ALTER TABLE pull_requests ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE pull_requests ADD CONSTRAINT pr_commit_pair CHECK (
    (baseline_sha IS NULL AND candidate_sha IS NULL) OR
    (baseline_sha IS NOT NULL AND candidate_sha IS NOT NULL AND baseline_sha <> candidate_sha));

CREATE TABLE ingested_artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    artifact_identifier varchar(128) NOT NULL,
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    schema_version integer NOT NULL CHECK (schema_version = 2),
    raw_json bytea NOT NULL CHECK (octet_length(raw_json) BETWEEN 1 AND 1048576),
    imported_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (repository_id, artifact_identifier),
    UNIQUE (id, repository_id)
);

CREATE TABLE benchmark_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    pull_request_id uuid NOT NULL,
    artifact_id uuid NOT NULL UNIQUE,
    workflow_run_id bigint NOT NULL CHECK (workflow_run_id BETWEEN 1 AND 9007199254740991),
    workflow_run_attempt integer NOT NULL CHECK (workflow_run_attempt > 0),
    commit_sha char(40) NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    baseline_sha char(40) NOT NULL CHECK (baseline_sha ~ '^[0-9a-f]{40}$'),
    candidate_sha char(40) NOT NULL CHECK (candidate_sha ~ '^[0-9a-f]{40}$'),
    branch varchar(200) NOT NULL CHECK (length(branch) > 0),
    measured_at timestamptz NOT NULL,
    environment jsonb NOT NULL CHECK (jsonb_typeof(environment) = 'object'),
    environment_sha256 char(64) NOT NULL CHECK (environment_sha256 ~ '^[0-9a-f]{64}$'),
    status varchar(10) NOT NULL CHECK (status IN ('COMPLETE', 'FAILED')),
    data_origin varchar(4) NOT NULL CHECK (data_origin IN ('REAL', 'DEMO')),
    provenance varchar(20) NOT NULL DEFAULT 'LOCAL_UNVERIFIED' CHECK (provenance = 'LOCAL_UNVERIFIED'),
    imported_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (pull_request_id, repository_id) REFERENCES pull_requests(id, repository_id),
    FOREIGN KEY (artifact_id, repository_id) REFERENCES ingested_artifacts(id, repository_id),
    UNIQUE (repository_id, workflow_run_id, workflow_run_attempt, commit_sha),
    UNIQUE (id, repository_id),
    CHECK (baseline_sha <> candidate_sha),
    CHECK (commit_sha IN (baseline_sha, candidate_sha))
);

CREATE TABLE benchmark_cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    identifier varchar(200) NOT NULL CHECK (identifier ~ '^[A-Za-z0-9_.$-]+$'),
    metric_name varchar(30) NOT NULL CHECK (metric_name IN ('average_time', 'throughput')),
    unit varchar(10) NOT NULL,
    direction varchar(6) NOT NULL CHECK (direction IN ('LOWER', 'HIGHER')),
    CHECK ((metric_name = 'average_time' AND unit = 'ns/op' AND direction = 'LOWER') OR
           (metric_name = 'throughput' AND unit = 'ops/s' AND direction = 'HIGHER')),
    UNIQUE (repository_id, identifier),
    UNIQUE (id, repository_id)
);

CREATE TABLE run_benchmarks (
    run_id uuid NOT NULL,
    case_id uuid NOT NULL,
    repository_id uuid NOT NULL,
    parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
    configuration jsonb NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    configuration_sha256 char(64) NOT NULL CHECK (configuration_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (run_id, case_id),
    FOREIGN KEY (run_id, repository_id) REFERENCES benchmark_runs(id, repository_id),
    FOREIGN KEY (case_id, repository_id) REFERENCES benchmark_cases(id, repository_id)
);

CREATE TABLE benchmark_samples (
    run_id uuid NOT NULL,
    case_id uuid NOT NULL,
    fork_index smallint NOT NULL CHECK (fork_index BETWEEN 0 AND 9),
    iteration_index smallint NOT NULL CHECK (iteration_index BETWEEN 0 AND 29),
    value double precision NOT NULL CHECK (value > 0 AND value <= 1e15),
    PRIMARY KEY (run_id, case_id, fork_index, iteration_index),
    FOREIGN KEY (run_id, case_id) REFERENCES run_benchmarks(run_id, case_id)
);

CREATE TABLE run_comparisons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    baseline_run_id uuid NOT NULL,
    candidate_run_id uuid NOT NULL,
    case_id uuid NOT NULL,
    analysis_version varchar(50) NOT NULL,
    analysis_status varchar(30) NOT NULL CHECK (analysis_status IN ('INSUFFICIENT_EVIDENCE', 'DESCRIPTIVE_ONLY', 'INCOMPATIBLE')),
    summary jsonb CHECK (summary IS NULL OR jsonb_typeof(summary) = 'object'),
    reasons jsonb NOT NULL CHECK (jsonb_typeof(reasons) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (baseline_run_id, repository_id) REFERENCES benchmark_runs(id, repository_id),
    FOREIGN KEY (candidate_run_id, repository_id) REFERENCES benchmark_runs(id, repository_id),
    FOREIGN KEY (case_id, repository_id) REFERENCES benchmark_cases(id, repository_id),
    FOREIGN KEY (baseline_run_id, case_id) REFERENCES run_benchmarks(run_id, case_id),
    FOREIGN KEY (candidate_run_id, case_id) REFERENCES run_benchmarks(run_id, case_id),
    UNIQUE (baseline_run_id, candidate_run_id, case_id, analysis_version),
    CHECK (baseline_run_id <> candidate_run_id),
    CHECK ((analysis_status = 'INCOMPATIBLE' AND summary IS NULL) OR
           (analysis_status <> 'INCOMPATIBLE' AND summary IS NOT NULL))
);

CREATE INDEX runs_pr_time ON benchmark_runs(pull_request_id, measured_at DESC, id);
CREATE INDEX runs_repo_origin_time ON benchmark_runs(repository_id, data_origin, measured_at DESC, id);
CREATE INDEX run_benchmarks_history ON run_benchmarks(case_id, configuration_sha256, run_id);
CREATE INDEX cases_repository ON benchmark_cases(repository_id, identifier);
CREATE INDEX run_comparisons_candidate ON run_comparisons(candidate_run_id, created_at DESC, id);
CREATE INDEX pull_requests_page ON pull_requests(repository_id, number DESC);
