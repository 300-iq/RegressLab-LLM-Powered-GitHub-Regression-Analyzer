import Link from "next/link";
import { PageHeading } from "@/components/page-heading";
import {
  CaseFilter,
  EmptyState,
  Pagination,
  Panel,
  resultFailure,
} from "@/components/results/ui";
import { Trends } from "@/components/results/trends";
import {
  attempt,
  getHistory,
  getRepositories,
  pageNumber,
  queryValue,
  type Query,
} from "@/lib/results";
import { repositoryWorkspace } from "@/lib/workspace";
async function loadTrends(props: { searchParams: Promise<Query> }) {
  const query = await props.searchParams,
    repo = (await getRepositories())[0];
  const workspace = repo
    ? await repositoryWorkspace(repo, 0, pageNumber(query, "runsPage"))
    : null;
  const selected = queryValue(query, "case");
  const benchmark = workspace?.cases.find((b) =>
    selected ? b.id === selected : true,
  );
  const history =
    benchmark && repo
      ? await getHistory(benchmark.id, repo, pageNumber(query))
      : null;
  const run = workspace?.details.find((detail) =>
    detail.benchmarks.some((b) => b.benchmarkCase.id === benchmark?.id),
  );
  return { query, repo, workspace, selected, benchmark, history, run };
}
export default async function TrendsPage(props: {
  searchParams: Promise<Query>;
}) {
  const result = await attempt(() => loadTrends(props));
  if (result.kind === "error") return resultFailure(result.error);
  const { query, repo, workspace, selected, benchmark, history, run } =
    result.value;
  return (
    <>
      <PageHeading
        eyebrow="WORKSPACE / HISTORY"
        title="Performance, over time."
        description="Inspect historical fork means while keeping differing environments and configurations separate."
      />
      <Panel
        title="Benchmark history"
        description={repo?.fullName}
        action={
          benchmark &&
          run && (
            <Link
              className="text-link"
              href={`/benchmarks/${benchmark.id}?run=${run.run.id}`}
            >
              Detailed measurements →
            </Link>
          )
        }
      >
        {workspace && (
          <div className="panel-controls">
            <CaseFilter
              cases={workspace.cases}
              selected={benchmark?.id}
              hidden={{ runsPage: String(workspace.runs.page) }}
            />
          </div>
        )}
        {history && benchmark ? (
          <>
            <Trends benchmark={benchmark} points={history.items} />
            <Pagination
              data={history}
              path="/trends"
              query={{ ...query, case: benchmark.id }}
              label="history observations"
            />
          </>
        ) : (
          <EmptyState
            title={
              selected
                ? "Case unavailable in this run window"
                : "No history available"
            }
          >
            Complete benchmark measurements are needed to discover a case. No
            trend or confidence band has been invented.
          </EmptyState>
        )}
        {workspace && (
          <Pagination
            data={workspace.runs}
            path="/trends"
            query={query}
            parameter="runsPage"
            label="case discovery runs"
          />
        )}
      </Panel>
      <div className="callout">
        <strong>A continuous line needs consistent conditions.</strong>
        <p>
          Each chart contains only matching environment and configuration
          hashes. Historical trends describe stored observations; they do not
          establish a cause or statistical significance.
        </p>
      </div>
    </>
  );
}
