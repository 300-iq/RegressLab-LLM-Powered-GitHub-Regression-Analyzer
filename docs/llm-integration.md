# Technical explanation: local LLM integration and limits

The model receives a bounded PR diff and benchmark evidence from the server. Its
reports describe possible code mechanisms and suggested experiments. Numerical
classifications remain separate; model output cannot upgrade an inconclusive result
or establish causation. Verification experiments require engineering review.

## Request and job handling

`POST /api/v1/comparisons/{id}/ai-investigations` takes `{analysisId}` and requires
local administration: opt-in profile, loopback binding/peer, strong bearer, allowlisted
repository and no forwarded requests. A short repeatable-read evidence snapshot
checks REAL/GITHUB_METADATA origin, current SHA/run/analysis relationships, complete
observations and a point effect reaching the stored practical threshold.

It returns **202** with a durable job ID/Location. GitHub metadata/diff retrieval and
inference occur in a bounded worker. Public read-only job/report endpoints never
call the model. A unique active-job index, database session lock and bounded executor
serialize normal inference; one queue slot covers handoff only. No message broker,
new microservice or public write proxy is introduced.

The worker rechecks authorization/snapshot, obtains the complete exact diff with
addition/deletion/hunk checks, and resolves the configured local model digest.
The cache key covers the full input, system/version and inference settings. A cache
hit must match the complete snapshot and valid citations; model identity is checked
again. A miss produces a new report. Report insertion and job success commit atomically
in a short READ_COMMITTED transaction, after network calls finish. Failed/interrupted
jobs never overwrite old reports; recovery does not automatically retry inference.

## Ollama transport and configuration

Spring RestClient uses Java HttpClient with no redirects and a three-second connect
limit. Outer deadlines cover full response-body reads, parsing and model metadata.
`REGRESSLAB_OLLAMA_TIMEOUT_SECONDS` selects 1–300 seconds; there are no automatic
inference retries. Offline, missing-model, timeout, malformed/truncated/oversized output,
changed model, busy and stale-evidence conditions become explicit failed job codes.

`REGRESSLAB_OLLAMA_URL` must be a loopback HTTP origin with an explicit port.
`REGRESSLAB_OLLAMA_MODEL` selects an installed local tag; obvious cloud tags and
remote-model metadata are rejected before repository content is sent. The native
daemon remains a trusted component: endpoint/tag checks cannot attest its internal
behavior, so use `OLLAMA_NO_CLOUD=1` and keep it private.

`REGRESSLAB_OLLAMA_CONTEXT_TOKENS` supports 8192–16384, default 16384. The maximum
prompt is 12,000 UTF-8 bytes and decreases conservatively for smaller contexts,
reserving 4096 output tokens and role overhead. Oversized input is rejected, never
silently summarized/truncated into misleading evidence. Chat uses JSON schema,
`stream:false`, `think:false`, temperature 0, seed 20261014 and `keep_alive:0`.
Options/timeout and installed model digest are preserved in the report snapshot.
Context and daemon parallelism affect memory; model size alone is not a RAM bound.
[Ollama FAQ](https://docs.ollama.com/faq) documents those controls.

## Structured output is not a truth certificate

Jackson enforces duplicate/unknown/trailing-field, type, text, depth and size rules.
The report has at most five hypotheses and explicit missing evidence. Each hypothesis
must cite the supplied comparison UUID and exact changed file/side/line; surrounding
unchanged lines are not invented as support. Every malformed/out-of-snapshot citation
rejects the entire new report. There are no model tools, shell/command callbacks,
code editing, deserialization into executable types or model-authored URL fetches.

These checks prove structure and location only. A model can cite a real line while
misunderstanding it, infer setup work instead of query work, invent a query plan,
misstate complexity or suggest invalid experiments. PR comments are untrusted data;
separate system instructions do not guarantee faithful interpretation. The controlled
suite found exactly those failures, including comment-execution fiction without
actual command execution. Use [the evaluation suite](../evaluation/README.md) to assess these failure modes.

The current demo uses three forks and preserves dependent iterations. Bootstrap
intervals are exploratory; no statistical significance or causal confidence is
calculated. Extra raw iterations cannot substitute for independent evidence. Reports
must not promote a numerical inconclusive result into a confirmed code cause.

A plausible mechanism needs matched, warmed, repeated independent verification
experiments and appropriate profiles before acting. Review workload scale, setup
versus query work, units and experimental controls; the model can omit those even
when prompted. Neither valid JSON, matching digests nor convincing prose establishes
correctness. Generated reports and evaluation evidence stay local, outside source
control.
