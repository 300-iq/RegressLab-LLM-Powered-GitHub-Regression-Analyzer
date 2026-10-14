# Benchmark artifact contract

The active single-run ingestion contract is
[benchmark-run-artifact.v2.schema.json](../contracts/benchmark-run-artifact.v2.schema.json).
A baseline artifact and a candidate artifact are imported before requesting a
comparison. Unknown fields, malformed metadata and invalid numerical data reject;
all uploaded content is inert, untrusted data.

## Single-run v2

An artifact records schemaVersion, artifactId, explicit REAL/DEMO origin, repository
identity, PR/commit snapshots, workflow run/attempt, branch/time/status, execution
environment and benchmark cases/configuration. Each individual observation retains
forkIndex, iterationIndex and value. Keep warmup outside measurement samples.

Java 21/JMH 1.37 supports average_time/ns/op/LOWER and throughput/ops/s/HIGHER.
Maximum upload is 1 MiB, depth 16, 20 cases and 300 samples/case (6000/run).
Values are finite, positive and <=1e15. Complete runs require cases and complete
contiguous unique fork/iteration grids matching configuration. Failed runs contain
no accepted primary measurements. Invalid zero/NaN/infinity/duplicate/conflicting
measurements reject; values are never synthesized or silently omitted.

IDs/indices require integer JSON literals in safe ranges, not floating-point
coercion. Timestamp range is 1970 inclusive to 2100 exclusive. Text is UTF-8 with
no NUL, escaped unpaired surrogates, duplicate keys or trailing JSON. Numeric
literals are limited to 32 characters. Exact semantic validation also runs in
ArtifactValidator; JSON Schema alone does not establish provenance.

Canonical-json-v1 sorts object keys recursively and retains array order for
configuration/environment hashing. Artifact identity uses exact original uploaded
bytes. JVM arguments, URLs and text are metadata only: never execute, fetch,
deserialize into executable types or evaluate them. Distinct metric identities,
units, directions and condition hashes cannot be silently compared.

## Original GitHub wrapper

Each approved public pipeline artifact ZIP contains exactly run.json (v2) and
execution.json (ledger v1). The compressed pair is <=512 KiB and expanded content
<=1 MiB/archive. No arbitrary filenames, directories, paths, duplicate entries or
invalid text are accepted. The backend never extracts or executes uploaded files.

The ledger records alternating-forks-v1 strategy/harness hash, six arm/fork blocks,
build phases, timestamps, duration/exit/error and actual observations. COMPLETE,
BUILD_FAILED, BENCHMARK_FAILED, TIMEOUT and NOT_RUN are explicit statuses; invalid
phase/status combinations reject. NOT_RUN has no process evidence; failures prevent
later blocks for the same arm from being treated as completed. Partial observations
remain audit evidence, never invented complete primary data. Primary sample values
must match ledger values exactly.

GitHub metadata/SHAs/PR association and original ZIP/pipeline digests are independently
checked. See [GitHub integration](github-integration.md). Manual imports remain
LOCAL_UNVERIFIED; verified origin is GITHUB_METADATA, not a promise of honest timing.

## Historical paired schema

[benchmark-artifact.schema.json](../contracts/benchmark-artifact.schema.json) is the
retained v1 paired-artifact design for legacy data/contract tests. It is not accepted
by active upload endpoints. New writes use v2 and the current PostgreSQL migrations.

See [statistical policy](statistical-analysis.md) for dependent iterations,
fork-level analysis, practical thresholds and uncertainty. Raw artifact metadata
cannot justify significance, sample independence or a code root cause.
