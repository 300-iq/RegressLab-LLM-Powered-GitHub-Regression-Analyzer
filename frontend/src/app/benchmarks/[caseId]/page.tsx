import Link from "next/link";
import { GithubExecution } from "@/components/results/github-execution";
import { PageHeading } from "@/components/page-heading";
import {
  Breadcrumb,
  EmptyState,
  MetricTag,
  Pagination,
  Panel,
  resultFailure,
} from "@/components/results/ui";
import { Trends } from "@/components/results/trends";
import { ComparisonView } from "@/components/results/comparison-view";
import { ForkTable, RunConditions } from "@/components/results/evidence";
import { RawSamples } from "@/components/results/raw-samples";
import { DistributionChart } from "@/components/results/charts";
import {
  attempt,
  ApiError,
  getAllSamples,
  getComparison,
  getHistory,
  getPrComparisons,
  getRepositories,
  getRepository,
  getRun,
  getRuns,
  pageNumber,
  queryValue,
  requireId,
  type Query,
} from "@/lib/results";
import { comparisonEvidence } from "@/lib/workspace";
import { latestComparisons } from "@/lib/presentation";
async function loadBenchmark(props: {
  params: Promise<{ caseId: string }>;
  searchParams: Promise<Query>;
}) {
  const { caseId } = await props.params,
    query = await props.searchParams;
  requireId(caseId);
  let runId = queryValue(query, "run");
  // Direct links carry run context because the API has no case-detail endpoint.
  // A bare case URL can still discover a case from the latest 20 stored runs.
  if (!runId) {
    const repo = (await getRepositories())[0];
    if (!repo) throw new ApiError("missing");
    const runs = await getRuns(repo);
    const details = await Promise.all(runs.items.map((run) => getRun(run.id)));
    runId = details.find((detail) =>
      detail.benchmarks.some((b) => b.benchmarkCase.id === caseId),
    )?.run.id;
  }
  if (!runId) throw new ApiError("missing");
  const detail = await getRun(runId),
    repo = await getRepository(detail.run.repositoryId);
  const benchmark = detail.benchmarks.find(
    (b) => b.benchmarkCase.id === caseId,
  );
  if (!benchmark) throw new ApiError("missing");
  const compatibleOnly = queryValue(query, "conditions") === "matching";
  const history = await getHistory(
    caseId,
    repo,
    pageNumber(query),
    compatibleOnly ? detail.run.environmentSha256 : undefined,
    compatibleOnly ? benchmark.configurationSha256 : undefined,
  );
  const samples = await getAllSamples(runId, caseId);
  const comparisons = await getPrComparisons(detail.run.pullRequestId);
  const selected = queryValue(query, "comparison");
  const comparison = selected
    ? await getComparison(selected)
    : latestComparisons(comparisons.items).find(
        (item) =>
          item.benchmarkCaseId === caseId &&
          (item.baselineRunId === runId || item.candidateRunId === runId),
      );
  if (
    comparison &&
    (comparison.benchmarkCaseId !== caseId ||
      (comparison.baselineRunId !== runId &&
        comparison.candidateRunId !== runId))
  )
    throw new ApiError("missing");
  const evidence = comparison ? await comparisonEvidence(comparison) : null;
  return {
    caseId,
    query,
    runId,
    detail,
    repo,
    benchmark,
    compatibleOnly,
    history,
    samples,
    selected,
    comparison,
    evidence,
  };
}
export default async function BenchmarkPage(props: {
  params: Promise<{ caseId: string }>;
  searchParams: Promise<Query>;
}) {
  const result = await attempt(() => loadBenchmark(props));
  if (result.kind === "error") return resultFailure(result.error);
  const {
    caseId,
    query,
    runId,
    detail,
    repo,
    benchmark,
    compatibleOnly,
    history,
    samples,
    selected,
    comparison,
    evidence,
  } = result.value;
  return (
    <>
      <Breadcrumb
        repositoryId={repo.id}
        repositoryName={repo.name}
        current="Benchmark detail"
      />
      <PageHeading
        eyebrow="BENCHMARK / JAVA · JMH"
        title={benchmark.benchmarkCase.identifier}
        description="Historical measurements, repeated-fork variability, and the exact recorded test conditions."
        action={<MetricTag benchmark={benchmark.benchmarkCase} />}
      />
      <Panel
        title="Historical trends"
        description="History includes commits from both baseline and candidate runs."
      >
        <form className="filter-form panel-controls">
          <input type="hidden" name="run" value={runId} />
          {selected && (
            <input type="hidden" name="comparison" value={selected} />
          )}
          <div className="field">
            <label htmlFor="conditions-filter">Test conditions</label>
            <select
              id="conditions-filter"
              name="conditions"
              defaultValue={compatibleOnly ? "matching" : "all"}
            >
              <option value="all">All conditions · grouped separately</option>
              <option value="matching">
                Match selected run environment & configuration
              </option>
            </select>
          </div>
          <button className="button button-primary">Apply filter</button>
        </form>
        <Trends points={history.items} benchmark={benchmark.benchmarkCase} />
        <Pagination
          data={history}
          path={`/benchmarks/${caseId}`}
          query={{ ...query, run: runId }}
          label="history observations"
        />
      </Panel>
      {evidence ? (
        <>
          <div className="section-heading">
            <h2>Detailed comparison</h2>
            <Link
              className="text-link"
              href={`/investigations/${detail.run.pullRequestId}?comparison=${comparison!.id}`}
            >
              Open PR investigation →
            </Link>
          </div>
          <ComparisonView evidence={evidence} />
        </>
      ) : (
        <>
          <Panel title="Detailed comparison">
            <EmptyState title="No stored comparison for this run and case">
              Single-run measurements are available below. Comparison
              computation remains a protected local operation.
            </EmptyState>
          </Panel>
          <Panel
            title="Measurement distribution"
            description="Raw iterations from the selected run."
          >
            <DistributionChart
              baselineLabel="Selected run iterations"
              baseline={samples.values}
              candidate={[]}
              unit={benchmark.benchmarkCase.unit}
            />
          </Panel>
          <Panel
            title="Repeat measurement variability"
            description="One mean per fresh JVM fork."
          >
            <ForkTable
              baseline={samples.values}
              candidate={[]}
              unit={benchmark.benchmarkCase.unit}
            />
          </Panel>
          <Panel title="Raw samples">
            <RawSamples
              baseline={samples.values}
              baselineLabel="Selected run"
              unit={benchmark.benchmarkCase.unit}
            />
          </Panel>
          <GithubExecution run={detail.run} />
          <RunConditions
            detail={detail}
            benchmark={benchmark}
            label="Selected run"
          />
        </>
      )}
    </>
  );
}
