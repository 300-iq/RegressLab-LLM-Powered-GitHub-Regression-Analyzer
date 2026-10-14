import Link from "next/link";
import { Suspense } from "react";
import { PageHeading } from "@/components/page-heading";
import { Breadcrumb, CaseFilter, EmptyState, MetricTag, Pagination, Panel, resultFailure } from "@/components/results/ui";
import { InvestigationWorkspace } from "@/components/results/investigation-workspace";
import { AiInvestigationLoader } from "@/components/results/ai-investigation-loader";
import { GithubExecution } from "@/components/results/github-execution";
import { RunConditions } from "@/components/results/evidence";
import { RawSamples } from "@/components/results/raw-samples";
import { DistributionChart } from "@/components/results/charts";
import { RunTable } from "@/components/results/tables";
import { loadInvestigation } from "@/lib/investigation-data";
import { attempt, type Query } from "@/lib/results";

export default async function InvestigationPage(props: { params: Promise<{ id: string }>; searchParams: Promise<Query> }) {
  const result = await attempt(async () => loadInvestigation((await props.params).id, await props.searchParams));
  if (result.kind === "error") return resultFailure(result.error);
  const data = result.value;
  const { pr, repo, evidence, inspected, catalog, singleBenchmark, singleSamples, selectedCase, query, path, detail } = data;
  const aiPanel=evidence?<Suspense fallback={<aside className="ai-investigation ai-empty" role="status"><h2>Loading stored investigations…</h2><p>Reading PostgreSQL reports. No model request has been started.</p></aside>}><AiInvestigationLoader data={data}/></Suspense>:null;
  return <>
    <Breadcrumb repositoryId={repo.id} repositoryName={repo.name} current={`PR #${pr.number}`} />
    {evidence ? <InvestigationWorkspace data={data} aiPanel={aiPanel} /> : <>
      <PageHeading eyebrow={`RUN INSPECTION / PR #${pr.number}`} title={pr.title} description="Inspect an individual run without inventing a paired comparison." />
      <div className="callout"><strong>No stored analysis selected.</strong><p>A single run cannot establish a performance change. Choose a stored pair from the investigation or inspect its measurements below.</p><Link className="text-link" href={path}>Back to investigation →</Link></div>
      {inspected ? <>
        <Panel title="Run measurements" description={`Commit ${inspected.run.commitSha} · ${inspected.run.status.toLowerCase()}`}>
          <div className="panel-controls"><CaseFilter cases={catalog} selected={singleBenchmark?.benchmarkCase.id} hidden={{ run: inspected.run.id }} /></div>
          {singleBenchmark && singleSamples ? <>
            <div className="panel-controls"><MetricTag benchmark={singleBenchmark.benchmarkCase} /><Link className="text-link" href={`/benchmarks/${singleBenchmark.benchmarkCase.id}?run=${inspected.run.id}`}>Benchmark detail →</Link></div>
            <DistributionChart baselineLabel="Selected run iterations" baseline={singleSamples.values} candidate={[]} unit={singleBenchmark.benchmarkCase.unit} />
          </> : <EmptyState title={selectedCase ? "Case unavailable in this run" : "No measured cases"}>Failed runs have no accepted result samples. Choose another run or case; partial execution evidence may be available below.</EmptyState>}
        </Panel>
        <GithubExecution run={inspected.run} />
        {singleBenchmark && singleSamples && <><Panel title="Raw samples"><RawSamples key={`${inspected.run.id}:${singleBenchmark.benchmarkCase.id}`} baseline={singleSamples.values} baselineLabel="Selected run" unit={singleBenchmark.benchmarkCase.unit} /></Panel><RunConditions detail={inspected} benchmark={singleBenchmark} label="Selected run" /></>}
      </> : <Panel title="Benchmark evidence"><EmptyState title="No runs imported">Each observation will retain its fork, iteration, commit and execution conditions when a benchmark artifact is imported.</EmptyState></Panel>}
      <Panel title="PR benchmark runs"><RunTable runs={detail.runs.items} /><Pagination data={detail.runs} path={path} query={query} parameter="runsPage" label="runs" /></Panel>
    </>}
  </>;
}
