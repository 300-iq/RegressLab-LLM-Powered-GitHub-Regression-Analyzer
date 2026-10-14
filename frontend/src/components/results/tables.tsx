import Link from "next/link";
import type {
  BenchmarkCase,
  Comparison,
  HistoryPoint,
  PullRequest,
  Run,
} from "@/lib/contracts";
import {
  direction,
  changeLabel,
  number,
  shortSha,
  timestamp,
} from "@/lib/presentation";
import { EmptyState, GithubLink, OriginBadge } from "./ui";

export function RunTable({ runs }: { runs: Run[] }) {
  if (!runs.length)
    return (
      <EmptyState title="No benchmark runs">
        Imported runs will appear here with their exact commit and execution
        environment.
      </EmptyState>
    );
  return (
    <div className="table-scroll" tabIndex={0} aria-label="Benchmark runs">
      <table>
        <caption className="sr-only">Recent benchmark runs</caption>
        <thead>
          <tr>
            <th scope="col">Commit / branch</th>
            <th scope="col">Pull request</th>
            <th scope="col">Measured</th>
            <th scope="col">Status</th>
            <th scope="col">Workflow</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr key={run.id}>
              <td>
                <Link
                  className="row-link"
                  href={`/investigations/${run.pullRequestId}?run=${run.id}`}
                >
                  <code>{shortSha(run.commitSha)}</code>
                </Link>
                <small>{run.branch}</small>
              </td>
              <td>
                <Link
                  className="text-link"
                  href={`/investigations/${run.pullRequestId}`}
                >
                  Investigate →
                </Link>
              </td>
              <td>
                <time dateTime={run.measuredAt}>
                  {timestamp(run.measuredAt)}
                </time>
              </td>
              <td>
                <span
                  className={`badge ${run.status === "FAILED" ? "badge-warning" : "badge-neutral"}`}
                >
                  {run.status === "FAILED" ? "Failed" : "Complete"}
                </span>
              </td>
              <td>
                <GithubLink url={run.githubUrl} origin={run.dataOrigin} provenance={run.provenance}>
                  Run {run.workflowRunId} · attempt {run.runAttempt}
                </GithubLink>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function PrTable({ prs }: { prs: PullRequest[] }) {
  if (!prs.length)
    return (
      <EmptyState title="No pull requests">
        Pull requests appear after benchmark artifacts are imported. GitHub
        synchronization is not implemented.
      </EmptyState>
    );
  return (
    <div className="table-scroll" tabIndex={0} aria-label="Pull requests">
      <table>
        <caption className="sr-only">Recent pull requests</caption>
        <thead>
          <tr>
            <th scope="col">Pull request</th>
            <th scope="col">Commits</th>
            <th scope="col">Source</th>
            <th scope="col">State</th>
          </tr>
        </thead>
        <tbody>
          {prs.map((pr) => (
            <tr key={pr.id}>
              <td>
                <Link className="row-link" href={`/investigations/${pr.id}`}>
                  #{pr.number} · {pr.title}
                </Link>
                <small>Updated {timestamp(pr.updatedAt)}</small>
              </td>
              <td>
                <code>
                  {shortSha(pr.baselineSha)} → {shortSha(pr.candidateSha)}
                </code>
              </td>
              <td>
                <OriginBadge origin={pr.dataOrigin} provenance={pr.provenance} />
              </td>
              <td>
                <span className="badge badge-neutral">
                  {pr.state.toLowerCase()}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function ComparisonTable({
  comparisons,
  cases,
  prId,
  selected,
}: {
  comparisons: Comparison[];
  cases: BenchmarkCase[];
  prId: string;
  selected?: string;
}) {
  if (!comparisons.length)
    return (
      <EmptyState title="No stored comparisons">
        Runs can be inspected independently. A trusted local operator must
        compute comparisons; this web application has no write credentials.
      </EmptyState>
    );
  const names = new Map(cases.map((benchmark) => [benchmark.id, benchmark]));
  return (
    <div className="table-scroll" tabIndex={0} aria-label="Stored comparisons">
      <table>
        <caption className="sr-only">Versioned benchmark analyses</caption>
        <thead>
          <tr>
            <th scope="col">Benchmark case</th>
            <th scope="col">Observed change</th>
            <th scope="col">Evidence</th>
            <th scope="col">Analysis version</th>
          </tr>
        </thead>
        <tbody>
          {comparisons.map((comparison) => (
            <tr
              key={comparison.id}
              className={
                selected === comparison.id ? "selected-row" : undefined
              }
            >
              <td>
                <Link
                  className="row-link"
                  href={`/investigations/${prId}?comparison=${comparison.id}`}
                >
                  {names.get(comparison.benchmarkCaseId)?.identifier ??
                    `Case ${comparison.benchmarkCaseId.slice(0, 8)}`}
                </Link>
                <small>{timestamp(comparison.createdAt)}</small>
              </td>
              <td>
                <span
                  className={
                    comparison.summary &&
                    comparison.summary.degradationPercent > 0
                      ? "observed-worse"
                      : ""
                  }
                >
                  {changeLabel(comparison)}
                </span>
                {names.get(comparison.benchmarkCaseId) && (
                  <small>
                    {names.get(comparison.benchmarkCaseId)!.unit} ·{" "}
                    {direction(
                      names.get(comparison.benchmarkCaseId)!.direction,
                    )}
                  </small>
                )}
              </td>
              <td>
                {comparison.analysisStatus === "INSUFFICIENT_EVIDENCE"
                  ? "Insufficient evidence"
                  : comparison.analysisStatus === "INCOMPATIBLE"
                    ? "Incompatible conditions"
                    : "Descriptive only"}
              </td>
              <td>
                <code>{comparison.analysisVersion}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function HistoryTable({
  points,
  benchmark,
}: {
  points: HistoryPoint[];
  benchmark: BenchmarkCase;
}) {
  return (
    <div className="table-scroll" tabIndex={0} aria-label="Benchmark history">
      <table>
        <caption className="sr-only">
          Historical fork means and comparison conditions
        </caption>
        <thead>
          <tr>
            <th scope="col">Measured / commit</th>
            <th scope="col">Mean ({benchmark.unit})</th>
            <th scope="col">Forks / samples</th>
            <th scope="col">Environment / configuration</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.run.id}>
              <td>
                <Link
                  className="row-link"
                  href={`/benchmarks/${benchmark.id}?run=${point.run.id}`}
                >
                  {timestamp(point.run.measuredAt)}
                </Link>
                <small>
                  <code>{shortSha(point.run.commitSha)}</code> ·{" "}
                  {point.run.branch}
                </small>
              </td>
              <td>{number(point.mean)}</td>
              <td>
                {point.forks} / {point.sampleCount}
              </td>
              <td>
                <code title={point.run.environmentSha256}>
                  {point.run.environmentSha256.slice(0, 10)}
                </code>
                <small>
                  <code title={point.configurationSha256}>
                    {point.configurationSha256.slice(0, 10)}
                  </code>
                </small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
