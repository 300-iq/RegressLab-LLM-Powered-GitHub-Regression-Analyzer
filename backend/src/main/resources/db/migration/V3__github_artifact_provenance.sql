-- Provenance describes checked GitHub origin, never statistical or measurement trust.
ALTER TABLE repositories DROP CONSTRAINT repositories_provenance_check;
ALTER TABLE repositories ADD CONSTRAINT repositories_provenance_check CHECK (provenance IN ('LOCAL_UNVERIFIED', 'GITHUB_METADATA'));
ALTER TABLE benchmark_runs DROP CONSTRAINT benchmark_runs_provenance_check;
ALTER TABLE benchmark_runs ADD CONSTRAINT benchmark_runs_provenance_check CHECK (provenance IN ('LOCAL_UNVERIFIED', 'GITHUB_METADATA'));
ALTER TABLE pull_requests ADD COLUMN provenance varchar(20) NOT NULL DEFAULT 'LOCAL_UNVERIFIED' CHECK (provenance IN ('LOCAL_UNVERIFIED', 'GITHUB_METADATA'));
ALTER TABLE run_comparisons ADD COLUMN provenance varchar(20) NOT NULL DEFAULT 'LOCAL_UNVERIFIED' CHECK (provenance IN ('LOCAL_UNVERIFIED', 'GITHUB_METADATA'));

CREATE TABLE github_artifact_imports (
    ingested_artifact_id uuid PRIMARY KEY REFERENCES ingested_artifacts(id),
    github_artifact_id bigint NOT NULL UNIQUE CHECK (github_artifact_id BETWEEN 1 AND 9007199254740991),
    archive_sha256 char(64) NOT NULL CHECK (archive_sha256 ~ '^[0-9a-f]{64}$'),
    archive_bytes bytea NOT NULL CHECK (octet_length(archive_bytes) BETWEEN 1 AND 524288),
    execution jsonb NOT NULL CHECK (jsonb_typeof(execution) = 'object'),
    verification_evidence jsonb NOT NULL CHECK (jsonb_typeof(verification_evidence) = 'object'),
    verified_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX github_imports_verified_at ON github_artifact_imports(verified_at DESC);
