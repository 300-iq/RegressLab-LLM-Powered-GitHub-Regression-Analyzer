import type {
  BenchmarkCase,
  Comparison,
  RunBenchmark,
  RunDetail,
  Sample,
} from "@/lib/contracts";
import {
  changeLabel,
  forkMeans,
  number,
  reasonLabel,
  shortSha,
  timestamp,
} from "@/lib/presentation";
import { GithubLink, MetricTag, OriginBadge, Panel } from "./ui";
export function EvidenceSummary({
  comparison,
  benchmark,
}: {
  comparison: Comparison;
  benchmark: BenchmarkCase;
}) {
  const summary = comparison.summary;
  return (
    <>
      <div className="evidence-heading">
        <div>
          <p className="eyebrow">SELECTED BENCHMARK</p>
          <h2 className="case-title">{benchmark.identifier}</h2>
        </div>
        <MetricTag benchmark={benchmark} />
      </div>
      <div className="answer-grid">
        <article className="answer-card">
          <p className="eyebrow">01 / DID PERFORMANCE CHANGE?</p>
          <strong
            className={`answer-value ${summary && summary.degradationPercent > 0 ? "observed-worse" : ""}`}
          >
            {changeLabel(comparison)}
          </strong>
          <p>
            {summary
              ? `${number(summary.baselineMean)} → ${number(summary.candidateMean)} ${benchmark.unit}`
              : "An effect cannot be computed across incompatible conditions."}
          </p>
          <span className="badge badge-neutral">
            Observed effect · No regression verdict
          </span>
        </article>
        <article className="answer-card">
          <p className="eyebrow">02 / HOW TRUSTWORTHY IS IT?</p>
          <strong className="answer-text">
            {comparison.analysisStatus === "INSUFFICIENT_EVIDENCE"
              ? "Insufficient evidence"
              : comparison.analysisStatus === "INCOMPATIBLE"
                ? "Incompatible conditions"
                : "Descriptive only"}
          </strong>
          <p>
            No statistical confidence or significance has been calculated. {comparison.provenance === "GITHUB_METADATA" ? "GitHub artifact origin is checked; measurement claims remain untrusted." : "Local provenance is unverified."}
          </p>
          <OriginBadge origin={comparison.dataOrigin} provenance={comparison.provenance} />
        </article>
        <article className="answer-card">
          <p className="eyebrow">03 / WHAT SUPPORTS THE OBSERVATION?</p>
          <strong className="answer-text">
            {summary
              ? `${summary.baselineForks} / ${summary.candidateForks} JVM forks`
              : "Inspect the run conditions"}
          </strong>
          <p>
            {summary
              ? `${summary.baselineSamples} baseline / ${summary.candidateSamples} candidate raw iterations. Fork means are the analysis units; shared runner noise remains.`
              : "Raw measurements remain available below."}
          </p>
          <a className="text-link" href="#raw-samples">
            Inspect raw samples ↓
          </a>
        </article>
      </div>
      <div className="evidence-limits" role="note">
        <strong>Evidence limits</strong>
        <ul>
          {comparison.reasons.map((reason) => (
            <li key={reason}>{reasonLabel(reason)}</li>
          ))}
        </ul>
        <span>
          Stored analysis <code>{comparison.analysisVersion}</code> ·{" "}
          {timestamp(comparison.createdAt)}
        </span>
      </div>
    </>
  );
}
export function ComparisonStatistics({
  comparison,
  unit,
}: {
  comparison: Comparison;
  unit: string;
}) {
  const summary = comparison.summary;
  if (!summary) return null;
  const rows = [
    [
      `Mean (${unit})`,
      number(summary.baselineMean),
      number(summary.candidateMean),
    ],
    [
      `Median (${unit})`,
      number(summary.baselineMedian),
      number(summary.candidateMedian),
    ],
    [
      `Sample standard deviation (${unit})`,
      number(summary.baselineStdDev),
      number(summary.candidateStdDev),
    ],
    [
      "Coefficient of variation",
      `${number(summary.baselineCv * 100)}%`,
      `${number(summary.candidateCv * 100)}%`,
    ],
    ["Fresh JVM forks", summary.baselineForks, summary.candidateForks],
    ["Raw iterations", summary.baselineSamples, summary.candidateSamples],
  ];
  return (
    <div
      className="table-scroll"
      tabIndex={0}
      aria-label="Comparison statistics"
    >
      <table>
        <caption className="sr-only">
          Statistics of fork means, calculated by the backend
        </caption>
        <thead>
          <tr>
            <th scope="col">Statistic of fork means</th>
            <th scope="col">Baseline</th>
            <th scope="col">Candidate</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, baseline, candidate]) => (
            <tr key={label}>
              <th scope="row">{label}</th>
              <td>{baseline}</td>
              <td>{candidate}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function ForkTable({
  baseline,
  candidate,
  unit,
}: {
  baseline: Sample[];
  candidate: Sample[];
  unit: string;
}) {
  const arms = [
    { name: "Baseline", samples: baseline },
    { name: "Candidate", samples: candidate },
  ];
  return (
    <div className="table-scroll" tabIndex={0} aria-label="Fork variability">
      <table>
        <caption className="sr-only">
          Repeat measurement variability by JVM fork
        </caption>
        <thead>
          <tr>
            <th scope="col">Arm</th>
            <th scope="col">Fork index</th>
            <th scope="col">Mean ({unit})</th>
            <th scope="col">Iterations</th>
          </tr>
        </thead>
        <tbody>
          {arms.flatMap((arm) =>
            forkMeans(arm.samples).map((fork) => (
              <tr key={`${arm.name}:${fork.fork}`}>
                <th scope="row">{arm.name}</th>
                <td>{fork.fork}</td>
                <td>{number(fork.value)}</td>
                <td>{fork.iterations}</td>
              </tr>
            )),
          )}
        </tbody>
      </table>
    </div>
  );
}
export function RunConditions({
  detail,
  benchmark,
  label,
}: {
  detail: RunDetail;
  benchmark: RunBenchmark;
  label: string;
}) {
  const run = detail.run;
  const environment = run.environment;
  const config = benchmark.configuration;
  const rows = [
    ["Commit SHA", run.commitSha],
    [
      "PR commit snapshot",
      `${shortSha(run.baselineSha)} → ${shortSha(run.candidateSha)}`,
    ],
    ["Branch", run.branch],
    ["Measured at", timestamp(run.measuredAt)],
    ["Runner", environment.runner],
    ["CPU", environment.cpu],
    ["OS / architecture", `${environment.os} / ${environment.architecture}`],
    ["Java / JMH", `${environment.javaVersion} / ${environment.jmhVersion}`],
    [
      "Forks / measured iterations",
      `${config.forkCount} / ${config.measurementIterations}`,
    ],
    ["Warmup", `${config.warmupIterations} × ${config.warmupTimeMs} ms`],
    [
      "Measurement",
      `${config.measurementIterations} × ${config.measurementTimeMs} ms`,
    ],
    [
      "Parameters",
      Object.entries(benchmark.parameters)
        .map(([key, value]) => `${key}=${value}`)
        .join(", ") || "None",
    ],
    ["JVM arguments (metadata)", config.jvmArgs.join(" ") || "None"],
    ["Environment SHA-256", run.environmentSha256],
    ["Configuration SHA-256", benchmark.configurationSha256],
    ["Artifact identifier", run.artifactIdentifier],
    ["Artifact SHA-256", run.artifactSha256],
    ["Imported at", timestamp(run.importedAt)],
  ];
  return (
    <Panel
      title={`${label} conditions`}
      description="Recorded metadata; no artifact content is executed."
    >
      <div className="conditions-body">
        <GithubLink url={run.githubUrl} origin={run.dataOrigin} provenance={run.provenance}>
          GitHub workflow {run.workflowRunId} · attempt {run.runAttempt}
        </GithubLink>
        <dl className="condition-list">
          {rows.map(([key, value]) => (
            <div key={key}>
              <dt>{key}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </Panel>
  );
}
