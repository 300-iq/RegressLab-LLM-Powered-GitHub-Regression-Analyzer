# Backend API

Spring JDBC on PostgreSQL, with Flyway migrations and explicit immutable DTOs.
Manual imports are LOCAL_UNVERIFIED; GITHUB_METADATA means independently checked
public artifact origin, not measurement honesty. DEMO fixtures are never auto-loaded.

## Endpoints

Paths are relative to `http://127.0.0.1:8080/api/v1`.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/health` | Process liveness |
| GET | `/overview` | Persisted counts with fixture labels |
| GET | `/repositories` | Repository array with identity/origin/provenance |
| GET | `/repositories/{id}/pull-requests` | Paginated PRs |
| GET | `/repositories/{id}/runs` | Paginated runs |
| GET | `/pull-requests/{id}` | PR plus paginated runs/comparisons |
| GET | `/pull-requests/{id}/runs` | PR runs |
| GET | `/pull-requests/{id}/comparisons` | PR comparisons |
| GET | `/runs/{id}` | Environment/provenance and benchmark cases/configuration |
| GET | `/runs/{runId}/cases/{caseId}/samples` | Raw fork/iteration values |
| GET | `/runs/{id}/github-audit` | Stored artifact IDs/digests, ledger and verification metadata |
| GET | `/cases/{caseId}/history` | Fork-level history across labeled conditions |
| POST | `/comparisons` | Protected descriptive computation/replay |
| GET | `/comparisons/{id}` | Stored descriptive comparison |
| POST | `/comparisons/{id}/analysis` | Protected bootstrap computation/replay |
| GET | `/comparisons/{id}/analyses` | Paginated immutable bootstrap snapshots |
| GET | `/comparison-analyses/{id}` | Exact bootstrap policy/result snapshot |
| POST | `/dev/artifacts` | Protected single-run v2 JSON import |
| POST | `/dev/github-imports` | Protected original GitHub ZIP pair import; opt-in |
| POST | `/comparisons/{id}/ai-investigations` | Protected async generation, `{ "analysisId": "UUID" }`; opt-in |
| GET | `/ai-investigation-jobs/{id}` | Durable job state |
| GET | `/comparisons/{id}/ai-investigation-jobs` | Paginated jobs |
| GET | `/ai-investigations/{id}` | Immutable unverified report and input evidence |
| GET | `/comparisons/{id}/ai-investigations` | Paginated completed reports |

`GET /actuator/health/readiness` checks database readiness outside `/api/v1`.
All writes are disabled by default. Enabled writes require local-ingestion profile,
loopback binding/direct peer, private 32–256 character bearer token, explicit
repository ID/name allowlist and bounded/rate-limited requests. Forwarded requests
are rejected. See [setup](local-setup.md) and [GitHub import](github-integration.md).

Pages use `{items,page,size,totalElements}`, zero-based page 0–100000, size 1–100.
Default size is 20, or 100 for samples. PRs sort by number descending; runs/history
by timestamp descending then UUID; samples by fork/iteration; stored comparisons,
analyses/jobs/reports by creation descending then UUID. Read page/count pairs use
repeatable-read snapshots. Historical lists default to origin=REAL; origin=DEMO
must be explicit. Direct reads retain origin/provenance. DEMO and REAL cannot mix
in one repository/database.

History supports optional environmentSha256/configurationSha256 filters (64 lowercase
hex characters). Matching labels do not attest matching dynamic runner load. Never
connect incompatible condition groups into a single trend.

## Errors

Responses use `application/problem+json`: type, title, status, detail, code and MVC
instance. Tokens, SQL, stack traces and supplied input values are not returned.

| HTTP | Meaning |
| --- | --- |
| 200 | Read or identical import/computation replay |
| 201 | Fresh artifact import; Location points to run |
| 202 | Accepted AI job; Location points to job |
| 400 | Malformed JSON/fields/query or invalid comparison request |
| 401 | Missing/wrong enabled-write bearer; WWW-Authenticate: Bearer |
| 403 | Disallowed repository or non-loopback/forwarded write |
| 404 | Missing resource or disabled write capability |
| 409 | Conflicting stable identity, incompatible requested run pair or stale evidence |
| 413 | Request/prompt byte budget exceeded |
| 415 | Unsupported content type/encoding |
| 422 | Invalid structured artifact values/counts/metadata |
| 429 | Rate/capacity limit |
| 502 | Invalid model/metadata response where applicable |
| 503 | Database/GitHub/provider unavailable; no unverified fallback |
| 504 | Provider deadline exceeded |

Logical async failure is an HTTP 200 read of a FAILED job, not a successful report.
Its errorCode/detail describe the failure; no replacement report is fabricated.

## Persistence and comparison semantics

Artifact idempotency uses `(repository,artifactId)` and
`(repository,workflowRunId,runAttempt,commitSha)`. Identical original bytes replay;
changed bytes under an existing identity conflict. One artifact contains all cases
for one run; no appending to imported runs. Write services serialize under advisory
transaction locks and READ_COMMITTED; constraints also enforce identity. Conflicts
roll back the complete repository/PR/run/artifact/case/sample transaction.

Each run preserves its exact PR/commit snapshot, artifact SHA-256 and configuration/
environment hashes. PR metadata may advance without rewriting old run/analysis data.
Original GitHub imports atomically preserve both arms, original ZIPs, execution
ledgers and verification evidence. The GitHub DTO contains workflowRunId,
baselineArtifactId, candidateArtifactId and base64 baselineArchiveChunks/
candidateArchiveChunks. Combined compressed bytes are limited to 512 KiB; JSON
requests still have a 1 MiB bound. Use `scripts/import-github.mjs` for encoding.

The retained fork-descriptive-v2 comparison summarizes fork means with scaled sample
variance. Positive direction-adjusted degradation means worse, not a regression
verdict. Different identities/units/directions are rejected; incompatible condition
hashes produce INCOMPATIBLE with no effect. Fewer than ten forks yields
INSUFFICIENT_EVIDENCE; ten still yields DESCRIPTIVE_ONLY for that version.

The separate fork-bootstrap-v2 endpoint accepts `{}` or
`{"practicalThresholdPercent":5}`. Immutable snapshots include policy/input hashes,
raw/fork summaries, interval method, classification, warnings and limitations. See
[statistical rules](statistical-analysis.md); the workspace consumes these stored
snapshots without implicitly recomputing them.

## AI jobs and reports

Generation accepts an eligible authorized REAL/GITHUB_METADATA snapshot and returns
202 JobDto immediately. Fields: id, comparisonId, analysisId, status, reportId,
reused, errorCode, detail, createdAt, updatedAt. States are QUEUED/RUNNING/SUCCEEDED/
FAILED. Identical active submissions return the same job; different active work is
rejected with AI_BUSY. Network/inference runs in a bounded worker outside database
transactions. Read-only job/report endpoints never invoke the model.

A successful job references an immutable report; insert and success commit atomically.
Failures preserve completed reports. There is no automatic inference retry. Cache
reuse rechecks authorization, current diff, full evidence/configuration and model
digest. See [LLM integration](llm-integration.md) for exact bounds and error handling.
