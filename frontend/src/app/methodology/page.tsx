import { PageHeading } from "@/components/page-heading";
export default function MethodologyPage() {
  return (
    <>
      <PageHeading
        eyebrow="WORKSPACE / METHODOLOGY"
        title="A number is only the beginning."
        description="Every performance conclusion must expose its observations and evidence limits."
      />
      <section className="panel content-panel prose">
        <h2>Observation and inference</h2>
        <p>
          The backend reports direction-aware effects and descriptive
          statistics. A positive degradation percentage means observed worse
          performance; it is not a regression verdict. The PR workspace reads stored
          fork-bootstrap-v2 intervals and classifications. It never substitutes an
          interval for failed adequacy checks, calculates a p-value or infers a root cause.
        </p>
        <h2>The analysis unit is a JVM fork</h2>
        <p>
          Iterations in one JVM are correlated. The backend averages iterations
          within each fork, then reports the mean, median, sample standard
          deviation and coefficient of variation across fork means. The
          raw-iteration histogram is a descriptive distribution, not an
          independent-sample significance test. Fresh JVMs do not prove independence from shared runner noise.
        </p>
        <h2>Evidence status</h2>
        <p>
          The retained descriptive method labels fewer than ten forks INSUFFICIENT_EVIDENCE
          and ten forks DESCRIPTIVE_ONLY. Stored bootstrap analyses additionally
          require ten forks per arm, five iterations per fork, five distinct fork
          means, CV at most 20%, verified origin and sensitivity checks before an
          indication. Otherwise results remain inconclusive. Incompatible conditions
          have no relative effect or interval. The actual three-fork demonstration
          remains inconclusive even for very large observed effects.
        </p>
        <h2>Conditional intervals and practical change</h2>
        <p>
          The selected statistic is relative change in the median of fork means,
          adjusted so positive values mean worse performance. The backend resamples
          whole fork means independently by arm, using 10,000 draws, a fixed seed
          and type-7 quantiles. Bonferroni interval tails cover the recorded case
          family. The displayed practical threshold belongs to the stored policy.
          Interval bounds must lie beyond that threshold for an indication or
          strictly inside its band for no meaningful change detected, with all
          eligibility checks passing.
        </p>
        <p>
          Nominal coverage is not guaranteed. Exchangeable independent forks and
          arms are assumptions; runner drift and dependence can violate them.
          Repeated PRs, reruns and post-hoc policy selection are not controlled.
          These conditional intervals are neither proof of causation nor universal
          statistical validity.
        </p>
        <h2>Compatible history</h2>
        <p>
          Trend lines only connect records with identical environment and
          configuration hashes for the same case and origin. A matching hash
          means matching recorded metadata, not proof of identical runtime noise
          or controlled execution. Shared runner noise and ordering effects are
          not eliminated by metadata checks.
        </p>
        <h2>Reproducible audit records</h2>
        <p>
          Raw artifacts, server-computed hashes, exact commit snapshots and
          analysis versions are preserved. Descriptive summaries use fork-descriptive-v2;
          conditional analyses use fork-bootstrap-v2. Older snapshots remain
          selectable audit records and are not silently replaced.
        </p>
        <h2>Origin and measurement trust</h2>
        <p>
          Protected local JSON imports are LOCAL_UNVERIFIED. GITHUB_METADATA means the server checked the public repository, PR snapshot, workflow, reviewed pipeline and original archive digest against GitHub. This verifies artifact origin, not the honesty of measurements from untrusted PR code. Hosted runner noise remains. DEMO records
          and measurements are synthetic; their fictional GitHub resources are
          labeled and are not linked as real results. This application only
          reads data and holds no ingestion credentials.
        </p>
      </section>
    </>
  );
}
