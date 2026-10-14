import Link from "next/link";
import { PageHeading } from "@/components/page-heading";
import {
  CaseFilter,
  EmptyState,
  GithubLink,
  Pagination,
  Panel,
  resultFailure,
} from "@/components/results/ui";
import { PrTable, RunTable } from "@/components/results/tables";
import { Trends } from "@/components/results/trends";
import {
  attempt,
  getHistory,
  getRepository,
  pageNumber,
  queryValue,
  type Query,
} from "@/lib/results";
import { repositoryWorkspace } from "@/lib/workspace";
async function loadRepository(props: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Query>;
}) {
  const { id } = await props.params,
    query = await props.searchParams;
  const repo = await getRepository(id),
    path = `/repositories/${id}`;
  const workspace = await repositoryWorkspace(
    repo,
    pageNumber(query, "prsPage"),
    pageNumber(query, "runsPage"),
  );
  const selected = queryValue(query, "case");
  const benchmark = selected
    ? workspace.cases.find((b) => b.id === selected)
    : workspace.cases[0];
  const history = benchmark
    ? await getHistory(benchmark.id, repo, pageNumber(query, "historyPage"))
    : null;
  const contextRun = benchmark
    ? workspace.details.find((detail) =>
        detail.benchmarks.some((b) => b.benchmarkCase.id === benchmark.id),
      )
    : undefined;
  return {
    repo,
    path,
    workspace,
    benchmark,
    history,
    contextRun,
    query,
    selected,
  };
}
export default async function RepositoryPage(props: {
  params: Promise<{ id: string }>;
  searchParams: Promise<Query>;
}) {
  const result = await attempt(() => loadRepository(props));
  if (result.kind === "error") return resultFailure(result.error);
  const {
    repo,
    path,
    workspace,
    benchmark,
    history,
    contextRun,
    query,
    selected,
  } = result.value;
  return (
    <>
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link href="/repositories">Repositories</Link>
        <span>/</span>
        <span aria-current="page">{repo.name}</span>
      </nav>
      <PageHeading
        eyebrow="REPOSITORY / JAVA BENCHMARKS"
        title={repo.fullName}
        description="Performance across commit snapshots, with environment and configuration boundaries preserved."
        action={
          <GithubLink url={repo.githubUrl} origin={repo.dataOrigin} provenance={repo.provenance}>
            GitHub repository
          </GithubLink>
        }
      />
      <Panel
        title="Benchmark history"
        description="Cases discovered from the current page of runs; histories include both PR arms."
        action={
          benchmark &&
          contextRun && (
            <Link
              className="text-link"
              href={`/benchmarks/${benchmark.id}?run=${contextRun.run.id}`}
            >
              Benchmark detail →
            </Link>
          )
        }
      >
        <div className="panel-controls">
          <CaseFilter
            cases={workspace.cases}
            selected={benchmark?.id}
            hidden={{ runsPage: String(workspace.runs.page) }}
          />
        </div>
        {benchmark && history ? (
          <>
            <Trends benchmark={benchmark} points={history.items} />
            <Pagination
              data={history}
              path={path}
              query={{ ...query, case: benchmark.id }}
              parameter="historyPage"
              label="history observations"
            />
          </>
        ) : (
          <EmptyState
            title={
              selected
                ? "Benchmark not in this run window"
                : "No benchmark cases yet"
            }
          >
            {selected
              ? "Choose a case from the filter, or open its benchmark detail from a run investigation."
              : "A completed benchmark run is needed before history can be displayed."}
          </EmptyState>
        )}
      </Panel>
      <Panel
        title="Recent pull requests"
        description="Imported PR metadata and exact baseline/candidate snapshots."
      >
        <PrTable prs={workspace.prs.items} />
        <Pagination
          data={workspace.prs}
          path={path}
          query={query}
          parameter="prsPage"
          label="pull requests"
        />
      </Panel>
      <Panel
        title="Benchmark runs"
        description="Browse older run pages to discover additional cases."
      >
        <RunTable runs={workspace.runs.items} />
        <Pagination
          data={workspace.runs}
          path={path}
          query={query}
          parameter="runsPage"
          label="runs"
        />
      </Panel>
    </>
  );
}
