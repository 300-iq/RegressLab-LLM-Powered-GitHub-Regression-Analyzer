import Link from "next/link";
import { PageHeading } from "@/components/page-heading";
import { ServiceNotice } from "@/components/service-notice";
import {
  EmptyState,
  OriginBadge,
  MetricTag,
  Panel,
  resultFailure,
} from "@/components/results/ui";
import { RunTable } from "@/components/results/tables";
import { Trends } from "@/components/results/trends";
import { getDashboard } from "@/lib/dashboard";
import { attempt, getHistory } from "@/lib/results";
import { changeLabel, latestComparisons } from "@/lib/presentation";
import { recentChanges } from "@/lib/workspace";

export default async function OverviewPage() {
  const data = await getDashboard();
  const metrics = [
    {
      label: "Repositories monitored",
      value: data.kind === "ready" ? data.overview.repositories : null,
      note: "One repository in this workspace",
    },
    {
      label: "Pull requests",
      value: data.kind === "ready" ? data.overview.pullRequests : null,
      note: "Imported PR commit snapshots",
    },
    {
      label: "Stored analyses",
      value: data.kind === "ready" ? data.overview.comparisons : null,
      note: "Includes retained analysis versions",
    },
    {
      label: "Raw observations",
      value: data.kind === "ready" ? data.overview.observations : null,
      note: "Individual measured iterations",
    },
  ];
  const result = await attempt(async () => {
    const repo = data.kind === "ready" ? data.repositories[0] : undefined;
    if (!repo) return null;
    const recent = await recentChanges(repo);
    const benchmark = recent.cases[0];
    const history = benchmark ? await getHistory(benchmark.id, repo) : null;
    // Only current PR commit pairs with known run metadata qualify for this list.
    const runMap = new Map(recent.runs.items.map((run) => [run.id, run]));
    const slowdowns = recent.comparisons.flatMap(({ pr, analyses }) =>
      latestComparisons(analyses.items)
        .filter((analysis) => {
          const baseline = runMap.get(analysis.baselineRunId),
            candidate = runMap.get(analysis.candidateRunId);
          return (
            analysis.summary &&
            analysis.summary.degradationPercent > 0 &&
            baseline?.commitSha === pr.baselineSha &&
            candidate?.commitSha === pr.candidateSha
          );
        })
        .map((analysis) => ({ pr, analysis })),
    );
    return { repo, recent, benchmark, history, slowdowns };
  });
  if (result.kind === "error") return resultFailure(result.error);
  const loaded = result.value;
  if (!loaded)
    return (
      <>
        <PageHeading
          eyebrow="PERFORMANCE WORKSPACE"
          title="A clearer view of every change."
          description="Inspect performance changes, understand evidence limits, and follow every observation."
        />
        <ServiceNotice data={data} />
        <Metrics metrics={metrics} />
        <Panel title="Performance investigations">
          <EmptyState
            title={
              data.kind === "ready"
                ? "Your first investigation starts here"
                : "Waiting for the performance API"
            }
          >
            Import benchmark measurements to inspect commit pairs, raw
            observations, and descriptive effects. No benchmark results are
            seeded or simulated.
          </EmptyState>
        </Panel>
      </>
    );
  const { repo, recent, benchmark, history, slowdowns } = loaded;
  const content = (
    <>
      <div className="dashboard-grid">
        <Panel
          title="Repositories monitored"
          description={repo.provenance === "GITHUB_METADATA" ? "Repository identity checked against public GitHub metadata." : "Stored identities; GitHub verification is pending."}
          action={
            <Link className="text-link" href="/repositories">
              View all →
            </Link>
          }
        >
          <Link className="repository-card" href={`/repositories/${repo.id}`}>
            <span className="repository-mark" aria-hidden="true">
              ⌘
            </span>
            <div>
              <strong>{repo.fullName}</strong>
              <p>
                {recent.runs.totalElements} runs · {recent.prs.totalElements}{" "}
                pull requests
              </p>
              <OriginBadge origin={repo.dataOrigin} provenance={repo.provenance} />
            </div>
            <span aria-hidden="true">↗</span>
          </Link>
        </Panel>
        <Panel
          title="Observed slowdowns"
          description="Possible regressions to investigate. Descriptive effects, not verdicts."
        >
          {slowdowns.length ? (
            <ul className="change-list">
              {slowdowns.map(({ pr, analysis }) => (
                <li key={analysis.id}>
                  <Link
                    href={`/investigations/${pr.id}?comparison=${analysis.id}`}
                  >
                    <strong>
                      #{pr.number} ·{" "}
                      {repo.dataOrigin === "DEMO"
                        ? "DEMO observation"
                        : "Performance observation"}
                    </strong>
                    <small>
                      {recent.cases.find(
                        (b) => b.id === analysis.benchmarkCaseId,
                      )?.identifier ?? "Stored benchmark case"}
                    </small>
                    {recent.cases.find(
                      (b) => b.id === analysis.benchmarkCaseId,
                    ) && (
                      <MetricTag
                        benchmark={
                          recent.cases.find(
                            (b) => b.id === analysis.benchmarkCaseId,
                          )!
                        }
                      />
                    )}
                  </Link>
                  <span className="observed-worse">
                    {changeLabel(analysis)}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState title="No observed slowdowns in this window">
              This checks stored analyses for the 5 most recent PRs and 6 most
              recent runs, using up to 20 stored analyses per PR. Missing
              analyses are not evidence of unchanged performance.
            </EmptyState>
          )}
          <p className="panel-footnote">
            {repo.dataOrigin === "DEMO"
              ? "Synthetic fixture effects are not real PR results. "
              : ""}
            Recent window only · up to 20 analyses per PR · no statistical
            significance calculated.
          </p>
        </Panel>
      </div>
      <Panel
        title="Recent benchmark runs"
        description="Latest six runs with exact commits and recorded conditions."
        action={
          <Link className="text-link" href={`/repositories/${repo.id}`}>
            Repository detail →
          </Link>
        }
      >
        <RunTable runs={recent.runs.items} />
      </Panel>
      <Panel
        title="Recent performance trends"
        description={
          benchmark?.identifier ??
          "History appears when measurements are available."
        }
        action={
          <Link className="text-link" href="/trends">
            Explore history →
          </Link>
        }
      >
        {benchmark && history ? (
          <Trends points={history.items} benchmark={benchmark} />
        ) : (
          <EmptyState title="No benchmark history">
            A completed run is needed to discover benchmark cases.
          </EmptyState>
        )}
        {history && history.totalElements > history.items.length && (
          <p className="panel-footnote">
            Showing the latest {history.items.length} of {history.totalElements}{" "}
            observations. Open benchmark detail for paginated history.
          </p>
        )}
      </Panel>
    </>
  );
  return (
    <>
      <PageHeading
        eyebrow="PERFORMANCE WORKSPACE"
        title="A clearer view of every change."
        description="Inspect performance changes, understand evidence limits, and follow every observation."
        action={
          <Link className="button button-primary" href="/investigations">
            Open investigations →
          </Link>
        }
      />
      <ServiceNotice data={data} />
      <Metrics metrics={metrics} />
      {content}
    </>
  );
}

function Metrics({
  metrics,
}: {
  metrics: { label: string; value: number | null; note: string }[];
}) {
  return (
    <section aria-label="Workspace metrics" className="metrics-grid">
      {metrics.map((metric) => (
        <article key={metric.label} className="metric-card">
          <div className="metric-label">{metric.label}</div>
          <div className="metric-value">
            {metric.value === null ? "—" : metric.value.toLocaleString("en-US")}
          </div>
          <p>{metric.note}</p>
        </article>
      ))}
    </section>
  );
}
