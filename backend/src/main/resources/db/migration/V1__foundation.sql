-- No fixtures: every dashboard count comes from stored data.
CREATE TABLE repositories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id bigint NOT NULL UNIQUE CHECK (github_id > 0),
    full_name varchar(140) NOT NULL UNIQUE
        CHECK (full_name ~ '^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}$'),
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pull_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    number integer NOT NULL CHECK (number > 0),
    title varchar(500) NOT NULL,
    state varchar(10) NOT NULL CHECK (state IN ('OPEN', 'CLOSED', 'MERGED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (repository_id, number),
    UNIQUE (id, repository_id)
);

CREATE TABLE comparisons (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    pull_request_id uuid NOT NULL,
    workflow_run_id bigint NOT NULL CHECK (workflow_run_id > 0),
    workflow_run_attempt integer NOT NULL CHECK (workflow_run_attempt > 0),
    baseline_sha char(40) NOT NULL CHECK (baseline_sha ~ '^[0-9a-f]{40}$'),
    candidate_sha char(40) NOT NULL CHECK (candidate_sha ~ '^[0-9a-f]{40}$'),
    artifact_sha256 char(64) NOT NULL CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    status varchar(10) NOT NULL CHECK (status IN ('PENDING', 'COMPLETE', 'REJECTED')),
    data_origin varchar(4) NOT NULL CHECK (data_origin IN ('REAL', 'DEMO')),
    environment jsonb NOT NULL CHECK (jsonb_typeof(environment) = 'object'),
    measured_at timestamptz NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (pull_request_id, repository_id) REFERENCES pull_requests(id, repository_id),
    UNIQUE (repository_id, workflow_run_id, workflow_run_attempt),
    CHECK (baseline_sha <> candidate_sha)
);

CREATE TABLE comparison_benchmarks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    comparison_id uuid NOT NULL REFERENCES comparisons(id) ON DELETE CASCADE,
    name varchar(200) NOT NULL,
    parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
    configuration jsonb NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    configuration_sha256 char(64) NOT NULL CHECK (configuration_sha256 ~ '^[0-9a-f]{64}$'),
    metric varchar(20) NOT NULL DEFAULT 'average_time' CHECK (metric = 'average_time'),
    unit varchar(5) NOT NULL DEFAULT 'ns/op' CHECK (unit = 'ns/op'),
    direction varchar(5) NOT NULL DEFAULT 'LOWER' CHECK (direction = 'LOWER'),
    UNIQUE (comparison_id, name, configuration_sha256)
);

CREATE TABLE observations (
    comparison_benchmark_id uuid NOT NULL REFERENCES comparison_benchmarks(id) ON DELETE CASCADE,
    arm varchar(9) NOT NULL CHECK (arm IN ('BASELINE', 'CANDIDATE')),
    fork_index smallint NOT NULL CHECK (fork_index BETWEEN 0 AND 9),
    iteration_index smallint NOT NULL CHECK (iteration_index BETWEEN 0 AND 29),
    value double precision NOT NULL CHECK (value > 0 AND value <= 1e15),
    PRIMARY KEY (comparison_benchmark_id, arm, fork_index, iteration_index)
);

CREATE INDEX comparisons_pr_history ON comparisons(pull_request_id, measured_at DESC);
CREATE INDEX comparisons_repo_history ON comparisons(repository_id, measured_at DESC);
CREATE INDEX benchmarks_history ON comparison_benchmarks(name, configuration_sha256, comparison_id);
