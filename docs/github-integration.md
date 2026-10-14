# GitHub benchmarking and artifact import

The approved public demonstration repository is
[300-iq/regresslab-java-demo](https://github.com/300-iq/regresslab-java-demo), immutable
GitHub ID 1417739588. `benchmarks/demo/` mirrors its Java 21/JMH 1.37 harness and
unprivileged workflow. RegressLab imports data; it never checks out or executes
uploaded PR code.

## Measurement protocol

Exactly two EventBenchmarks average-time ns/op cases execute on isolated
GitHub-hosted runners. Each arm uses three fresh JVM forks, three warmup iterations
and five one-second measurements per fork. Baseline/candidate fork blocks alternate,
with starting arm derived from workflow identity. Raw observations and build/fork
failure/timeout statuses are preserved. Code construction/setup is outside the
measured operation; return values are consumed.

`pull_request` executes candidate code with contents:read, SHA-pinned Actions and
persist-credentials:false. No application/database/deployment secrets, privileged
self-hosted runner or pull_request_target execution is used. A hard runner/job
termination can prevent artifact publication even though upload normally uses always().

An existing demonstration is [PR #3](https://github.com/300-iq/regresslab-java-demo/pull/3)
and [workflow 38600700268](https://github.com/300-iq/regresslab-java-demo/actions/runs/38600700268),
which measures a per-query map copy. Three forks per arm remain inconclusive;
matching hashes do not establish independent/honest measurements or remove noise.

## Obtain original archives

Use trusted local GitHub administration, not untrusted PR code. The importer checks
current run/PR/artifact metadata and original ZIP digests. Download import archives
locally; do not extract and re-zip before import. The two small archives under
backend/src/test/resources/github are historical test fixtures, not substitutes
for current GitHub verification. `gh run download` extracts content and cannot
reproduce the original ZIP digest.

For the existing workflow, while artifacts are available and current metadata still
matches, an authenticated GitHub CLI can download the original archives:

```sh
mkdir -p artifacts/pr3
chmod 700 artifacts/pr3
gh api /repos/300-iq/regresslab-java-demo/actions/artifacts/11913501591/zip > artifacts/pr3/baseline.zip
gh api /repos/300-iq/regresslab-java-demo/actions/artifacts/11913606416/zip > artifacts/pr3/candidate.zip
```

`artifacts/` is ignored. IDs above are genuine historical resources, not a promise
of permanent availability. Expired artifacts, changed PRs/attempts or changed approved
pipeline files are legitimate rejections. Obtain a new approved workflow's original
archives rather than disabling verification.

## Protected import

Follow [local setup](local-setup.md) to start actual PostgreSQL and the loopback API
with local-ingestion, a private bearer, repository allowlist and GitHub import opt-in.
Load the same private environment in the import terminal:

```sh
export REGRESSLAB_API_URL=http://127.0.0.1:8080
node scripts/import-github.mjs 38600700268 11913501591 11913606416 \
  artifacts/pr3/baseline.zip artifacts/pr3/candidate.zip > artifacts/pr3/import.json
```

The script reads back runs/comparisons/audit. Use returned UUIDs; fresh databases do
not share IDs. Create a stored bootstrap analysis for the desired comparison:

```sh
curl --fail-with-body -X POST "$REGRESSLAB_API_URL/api/v1/comparisons/$COMPARISON_ID/analysis" \
  -H "Authorization: Bearer $REGRESSLAB_INGESTION_TOKEN" -H 'Content-Type: application/json' \
  --data '{}'
```

The frontend shows imported real records, exact PR/workflow/commit links and raw
measurements. For AI, select an eligible comparison and its stored analysis through
the protected local frontend. Inconclusive measurements stay inconclusive.

## Verification boundaries

The fixed-origin GitHub client checks public repository ID/name, pull_request event,
run association/attempt/conclusion, workflow identity/path, current PR snapshot,
exact commit SHAs/branches, artifact IDs/names and original archive SHA-256.
Five workflow/harness/build blob pins in github-demo-pipeline.json must match both
commits. Changes require deliberate review/republishing; pins never auto-upgrade.

Each archive contains exactly run.json (v2) and execution.json (ledger v1).
Compressed pair <=512 KiB; expanded content <=1 MiB per archive. Paths/directories,
duplicates, malformed UTF-8/JSON/numbers/configuration/order and primary samples
conflicting with ledger values reject. No archive is extracted into executable
files or executed. The original ZIPs, raw samples and metadata audit commit
atomically in PostgreSQL. Stable replay preserves identifiers and original audit;
conflicts roll back both arms.

GITHUB_METADATA is checked artifact origin, not measurement attestation. Untrusted
PR code can influence runner/output data. Manual JSON remains LOCAL_UNVERIFIED.
GitHub failures/rate limits return errors without a fallback trust upgrade. Reads
of already stored results remain available after artifact expiration.

Import is administrator-triggered: no webhook/poller, privileged workflow_run job
or public unauthenticated write endpoint. Only the approved Java/JMH pipeline is
accepted; this is not a general remote code execution service.
