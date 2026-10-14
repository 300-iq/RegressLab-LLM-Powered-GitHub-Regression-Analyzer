# Conditional noise-aware analysis: fork-bootstrap-v2

This engine estimates a workload-specific relative change under recorded conditions.
It does not calculate p-values, claim statistically significant results, identify
root causes or attest that an untrusted benchmark honestly measured its code.
Old `fork-descriptive-v1/v2` and `fork-bootstrap-v1` records remain readable and
unchanged. Version v2 corrects invalid-effect handling and excludes subnormal
measurement scales; replay also fingerprints provenance.

## Measurements and estimand

Every accepted individual observation remains in PostgreSQL with its original
fork and iteration indices. Warmup iterations are excluded by the artifact contract;
the engine also requires a positive recorded warmup count. Warmup convergence is
not established by this metadata.

Each response reports raw mean, median, sample standard deviation, coefficient
of variation and count, **and separately** the same statistics of JVM-fork means.
Standard deviation uses n−1 with scaled deviations to avoid squaring tiny values.
Both arms carry their own units/directions. Complete, contiguous, unique fork/
iteration grids and finite positive measurements ≤1e15 are mandatory. Zero values
are invalid for the supported latency/throughput metrics; no arbitrary epsilon,
missing-value replacement or silently dropped observations are used. Analysis v2
requires measurements ≥ Double.MIN_NORMAL (approximately 2.225074e−308). Subnormal
values remain stored as raw artifact evidence, but summaries and inference return
INVALID_COMPARISON: means/deviations at that scale can round enough to cross the
noise guard. Finite normal tiny values (for example 1e−200) remain supported.

The selected statistic is the **median of fork means**, not the median of all
iterations. Each JVM fork receives equal weight. Let B and C be baseline and
candidate medians of these fork means:

- LOWER (average_time, ns/op): relative degradation = 100 × (C/B − 1).
- HIGHER (throughput, ops/s): relative degradation = 100 × (1 − C/B).

Positive is worse; negative is better. Both use B as the denominator. For example,
a 20% throughput increase is −20% degradation, not −16.7%. The selected statistic
differs deliberately from the older mean-based descriptive effect. Extremely small
positive values are retained, but an unrepresentable ratio invalidates the comparison.

## Reproducible interval

For each of 10,000 resamples, independently draw N baseline fork means with
replacement and M candidate fork means with replacement. Calculate their medians
and the direction-adjusted relative change above. Sort the estimates and take
two-sided percentile endpoints. Quantiles use type-7 linear interpolation:
position = p × (R−1), interpolating adjacent ordered estimates.

The generator is Java `java.util.Random`, fixed seed **20261013**, local to each
calculation. Fork means are ordered by original fork index; reductions within
forks use iteration index. Input order therefore cannot change the result.
The method, statistic, seed, resample count, quantile convention, nominal confidence
level, version, threshold, input SHA-256 and policy SHA-256 are persisted and returned.
The seed/resample count/adequacy guards cannot be tuned through the public DTO.

Use Bonferroni tails α/(2K), α=0.05, where K is the number of benchmark cases
present in **both** immutable run artifacts, including cases not requested by the
caller. K=1 gives nominal 95%; the actual two-case demo gives nominal 97.5% per
interval. This does not control repeated PRs/reruns or post-hoc selection of
thresholds, data, benchmark cases or methods. Adjustment cannot repair an invalid
bootstrap interval or prove its family-wise coverage.

The resampling unit is an entire **fork mean**. Repeating iterations within the
same fork does not increase the bootstrap unit count. Fork labels across arms
do not establish matched pairs; this version does not apply a paired bootstrap.

The [R boot reference](https://search.r-project.org/CRAN/refmans/boot/html/boot.ci.html)
describes percentile intervals as one of several nonparametric interval methods;
it does not validate this benchmark-specific estimator. Cluster-oriented
resampling is also described by the [ClusterBootstrap package reference](https://search.r-project.org/CRAN/refmans/ClusterBootstrap/html/clusbootglm.html).
Those sources motivate respecting clusters; they are not a coverage guarantee
for JVM forks or noisy hosted runners.

## Conservative decision rules

Default practical threshold T is **5%**, configurable from 0.1% through 50% per
request. Threshold selection should precede looking at results. Different thresholds
produce separate immutable analysis records, rather than rewriting a verdict.

Before any indication, require all of:

- Exact case identifier, supported metric/unit/direction, environment hash and
  configuration/parameter hash match. Runs belong to an already validated exact
  baseline/candidate PR snapshot; old comparison identity validation still applies.
- Complete raw observations for every configured fork and measured iteration.
  The existing artifact contract supports 3–10 forks and 5–30 iterations per fork.
- At least **10 forks per arm** and **5 iterations per fork**. Ten forks is a
  conservative product floor, not proof of independence or sufficient coverage.
- At least **5 distinct fork means per arm**, to guard against degenerate/quantized
  empirical distributions that imply spurious precision.
- Between-fork sample CV ≤ **0.20 in both arms**. This is an explicit heuristic
  noise guard, not a hypothesis test or guarantee of low unrecorded noise.
- Dropping any one fork from either arm does not change the practical interpretation
  of the observed point effect. This sensitivity guard does not certify robustness.
- Verified execution-origin metadata. Local/unverified artifact execution/order
  remains exploratory even if numerical checks pass. Checked GitHub origin is
  not independence or honesty attestation.

Let L,U be interval endpoints on the signed degradation percentage scale:

| API classification | Human label | Decision after gates pass |
| --- | --- | --- |
| REGRESSION_INDICATED | Regression indicated | L > +T |
| IMPROVEMENT_INDICATED | Improvement indicated | U < −T |
| NO_MEANINGFUL_CHANGE_DETECTED | No meaningful change detected | L > −T and U < +T |
| INCONCLUSIVE | Inconclusive | An adequacy/noise/protocol guard fails, or the interval crosses/touches a practical boundary |
| INVALID_COMPARISON | Invalid comparison | Incompatible identities/units/environments/configurations, missing/duplicate/invalid observations or non-finite numerical effect |

Equality at a boundary is inconclusive. An interval containing zero does **not** by
itself imply no meaningful change. An interval excluding zero does **not** by itself
indicate a practical regression. No meaningful change detected means the conditional
interval is inside the chosen band; it is not proof of identical performance.

For otherwise valid 3–9-fork runs, an exploratory interval is returned to show the
empirical resampling calculation, with `exploratory:true` and INCONCLUSIVE. It must
never override sample-adequacy warnings. Invalid comparisons have no interval or
relative effect. Noisy valid runs can also expose an exploratory interval while
remaining inconclusive. Summaries of independently valid arms remain available
on an environment/configuration mismatch, with arm-specific units.

## Limitations and assumptions

Within-fork iterations are correlated. The model assumes **between-fork** units are
exchangeable and independent within arms and the two arms are independent. Fresh
JVM processes on the same hosted runner do not establish these assumptions.
Temporal drift, shared CPU contention, thermal state, cross-arm correlation,
autocorrelation, GC conditions and the chosen ordering can make percentile coverage
wrong. Alternating execution reduces some order effects without eliminating them.
Hashes identify recorded conditions; they do not observe actual load or noise.

Bootstrap resampling cannot discover unobserved failure modes, compensate for
cherry-picked reruns or make a small empirical distribution representative. Medians
of fork means are robust to some extreme forks but can miss changes in tails,
allocation, construction cost or the within-fork distribution. The artifact's
warmup count cannot prove steady-state convergence. Shared runner noise is not
modeled as an independently replicated workflow session by this version.

Directional classifications are conditional **indications**, not universal claims,
causal attribution or a statistically significant verdict. The current real demo
has three forks per arm and always remains inconclusive. Obtaining more forks or
independent workflow sessions requires a separately reviewed benchmark-protocol
change; the analysis engine does not change the runner or approved pipeline.

## Additive API and persistence

1. Use existing protected `POST /api/v1/comparisons` with the exact run pair/case
   to obtain a descriptive comparison ID.
2. Protected `POST /api/v1/comparisons/{id}/analysis`, JSON `{}` or
   `{"practicalThresholdPercent":5}`, computes/replays `fork-bootstrap-v2`.
3. Read its `Location` with `GET /api/v1/comparison-analyses/{id}`.

HTTP 200 returns persisted compute/replay with a Location header. Invalid request
fields/thresholds return 400, missing comparison/analysis 404, anonymous enabled
writes 401; writes are 404 when local ingestion is disabled. Domain-invalid data
returns a stored INVALID_COMPARISON result. All writes retain the existing direct
loopback/profile/token/size/rate-limit protection. There is no public compute proxy.

Stored summary readers independently allow up to 400 numeric characters because
PostgreSQL JSONB expands short exponent notation into decimal text. Public
MVC/artifact parsing still caps numeric literals at 32 characters; no upload
limit was relaxed.

The response contains `id`, `comparisonId`, `analysisVersion`, `policySha256`,
`inputSha256`, `policy`, `result`, `dataOrigin`, `provenance`, and `createdAt`.
`result` includes baseline/candidate summaries, signed relative change, interval
metadata, classification, a plain-language explanation, reason codes, environment
warnings and limitations. Raw measurements remain accessible through existing
paginated sample endpoints. `dataOrigin:DEMO` remains explicit on synthetic records.

Flyway V4 added `comparison_analyses`. V5 keys snapshots uniquely by comparison,
version, policy digest **and input digest**, and enforces non-null JSON types for
the threshold/classification fields. The v2 input digest covers both original
artifact hashes, conditions, ordered complete raw sample grids, data origin and
the combined provenance used by the execution-protocol gate. That provenance is
frozen in the response/row, making its original assessment reproducible even if
runs later gain GitHub verification. Existing immutable run/case/artifact records
provide the other inputs. A provenance upgrade creates a new snapshot; unchanged
inputs/policy replay the same ID. Historical v1 hashes use their original recipe. Read transactions remain repeatable-read;
writes explicitly use READ_COMMITTED and the existing advisory lock so concurrent
replay remains idempotent even under repeatable-read connection defaults.

The investigation workspace consumes these stored snapshots, keeps observed effects
separate from classifications and displays raw samples, fork counts, warnings and
exploratory intervals. It never computes an independent frontend verdict or treats
model hypotheses as evidence that upgrades the analysis.
