# Development fixtures (synthetic)

These two artifacts are **DEMO development fixtures**, generated deterministically
for building/testing the UI. They are not measured benchmark results. The repository,
workflow/PR IDs, SHAs, and environment are fictional. Constructed GitHub URLs are
not evidence of actual GitHub resources. Every imported row is labeled DEMO and
LOCAL_UNVERIFIED. These values are unsuitable for statistical calibration or
claims about measured regressions.

The application never automatically loads fixtures. Explicitly start the protected
local ingestion profile in an empty development database, allowlisting synthetic
ID `1` and `regresslab-demo/java-benchmarks`, then POST these JSON files with your
bearer token. See [the API guide](../../docs/backend-api.md). Repeated uploads of
identical bytes are idempotent; regenerated changed fixtures need new artifact/run
identities or a separate disposable database.

Includes two Java/JMH-shaped cases: average-time map lookup and throughput JSON
encoding, each with three forks × five iterations in both baseline/candidate runs.

Regenerate with `node backend/fixtures/generate-demo.mjs`.
