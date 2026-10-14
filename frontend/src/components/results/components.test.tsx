import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { comparisonSchema, type BenchmarkCase } from "@/lib/contracts";
import { EvidenceSummary, ComparisonStatistics } from "./evidence";
import { ExecutionEvidence } from "./github-execution";
import { githubAuditSchema, runSchema } from "@/lib/contracts";
import { RawSamples } from "./raw-samples";
import { EmptyState, GithubLink, MetricTag, Pagination } from "./ui";
const capture = JSON.parse(
  readFileSync(
    new URL("../../test-fixtures/synthetic-comparisons.json", import.meta.url),
    "utf8",
  ),
);
const comparison = comparisonSchema.parse(capture.comparisons[0]);
const benchmark: BenchmarkCase = {
  id: comparison.benchmarkCaseId,
  repositoryId: comparison.repositoryId,
  identifier: "DEMO.SyntheticCase",
  metricName: "throughput",
  unit: "ops/s",
  direction: "HIGHER",
};
describe("investigation components", () => {
  it("answers change, evidence quality, and supporting counts immediately", () => {
    const html = renderToStaticMarkup(
      <EvidenceSummary comparison={comparison} benchmark={benchmark} />,
    );
    expect(html).toContain("worse");
    expect(html).toContain("Insufficient evidence");
    expect(html).toContain("3 / 3 JVM forks");
    expect(html).toContain("15 baseline / 15 candidate");
    expect(html).toContain("No regression verdict");
    expect(html).toContain("DEMO · Synthetic");
    expect(html).toContain(
      "No confidence interval or significance test has been calculated.",
    );
    expect(html).toContain('href="#raw-samples"');
  });
  it("does not display a fabricated effect for incompatible runs", () => {
    const incompatible = {
      ...comparison,
      summary: null,
      analysisStatus: "INCOMPATIBLE" as const,
      reasons: ["ENVIRONMENT_MISMATCH"],
    };
    const html = renderToStaticMarkup(
      <EvidenceSummary comparison={incompatible} benchmark={benchmark} />,
    );
    expect(html).toContain("Not comparable");
    expect(html).toContain("Execution environments differ.");
    expect(html).not.toContain("% worse");
    expect(
      renderToStaticMarkup(
        <ComparisonStatistics comparison={incompatible} unit="ops/s" />,
      ),
    ).toBe("");
  });
  it("labels descriptive-only analysis without calling it sufficient confidence", () => {
    const html = renderToStaticMarkup(
      <EvidenceSummary
        comparison={{ ...comparison, analysisStatus: "DESCRIPTIVE_ONLY" }}
        benchmark={benchmark}
      />,
    );
    expect(html).toContain("Descriptive only");
    expect(html).toContain(
      "No statistical confidence or significance has been calculated.",
    );
  });
  it("keeps metric units and direction visible", () => {
    expect(renderToStaticMarkup(<MetricTag benchmark={benchmark} />)).toContain(
      "Higher is better",
    );
    expect(
      renderToStaticMarkup(
        <MetricTag
          benchmark={{
            ...benchmark,
            unit: "ns/op",
            direction: "LOWER",
            metricName: "average_time",
          }}
        />,
      ),
    ).toContain("Lower is better");
  });
  it("does not turn fictional demo GitHub resources into links", () => {
    const html = renderToStaticMarkup(
      <GithubLink origin="DEMO" url="https://github.com/fixture/demo">
        PR #42
      </GithubLink>,
    );
    expect(html).toContain("fictional demo resource");
    expect(html).not.toContain("<a");
    expect(
      renderToStaticMarkup(
        <GithubLink origin="REAL" url="https://github.com/fixture/demo">
          PR
        </GithubLink>,
      ),
    ).toContain("Unverified");
  });
  it("labels checked GitHub origin without promising measurement or statistical trust", () => {
    const html = renderToStaticMarkup(<EvidenceSummary comparison={{...comparison,dataOrigin:"REAL",provenance:"GITHUB_METADATA"}} benchmark={benchmark}/>);
    expect(html).toContain("GitHub metadata checked");
    expect(html).toContain("measurement claims remain untrusted");
    expect(html).toContain("Insufficient evidence");
    expect(html).not.toContain("Local provenance is unverified");
    expect(renderToStaticMarkup(<GithubLink origin="REAL" provenance="GITHUB_METADATA" url="https://github.com/300-iq/regresslab-java-demo/pull/1">PR</GithubLink>)).toContain("Metadata checked");
  });
  it("renders preserved execution order and explicit timeout evidence", () => {
    const capture = JSON.parse(readFileSync(new URL("../../test-fixtures/github-pr1-results.json",import.meta.url),"utf8"));
    const audit=githubAuditSchema.parse(capture.audit);
    const run=runSchema.parse(capture.baseline.run);
    audit.execution.builds.CANDIDATE={...audit.execution.builds.CANDIDATE,status:"TIMEOUT",exitCode:-9,error:"Correctness check exceeded its time limit"};
    audit.execution.blocks=audit.execution.blocks.map(block=>block.arm==="CANDIDATE"?{...block,status:"NOT_RUN",exitCode:null,startedAt:null,finishedAt:null,elapsedMs:0,observations:[],error:"Build did not complete"}:block);
    const html=renderToStaticMarkup(<ExecutionEvidence audit={audit} run={run}/>);
    expect(html).toContain("TIMEOUT");expect(html).toContain("Correctness check exceeded its time limit");
    expect(html).toContain("Reported GitHub benchmark execution order");
    expect(html).toContain("cannot prove measurements are honest");
    expect(html).toContain(run.githubUrl);
  });
  it("renders exact raw values, indexed rows, accessible arm controls, and empty observations", () => {
    const html = renderToStaticMarkup(
      <RawSamples
        baseline={[{ forkIndex: 0, iterationIndex: 0, value: 1.23456789 }]}
        candidate={[{ forkIndex: 0, iterationIndex: 0, value: 2 }]}
        unit="ns/op"
      />,
    );
    expect(html).toContain("1.23456789");
    expect(html).toContain("Fork index");
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain("Value (ns/op)");
    expect(
      renderToStaticMarkup(<RawSamples baseline={[]} unit="ns/op" />),
    ).toContain("No observations stored");
  });
  it("keeps pagination context and labels empty states without inventing outcomes", () => {
    const html = renderToStaticMarkup(
      <Pagination
        data={{ items: [], page: 1, size: 20, totalElements: 45 }}
        path="/trends"
        query={{ case: benchmark.id }}
        label="history observations"
      />,
    );
    expect(html).toContain("Page 2 of 3");
    expect(html).toContain(`${benchmark.id}&amp;page=2`);
    expect(
      renderToStaticMarkup(
        <EmptyState title="No comparisons">
          Missing analysis is not evidence of unchanged performance.
        </EmptyState>,
      ),
    ).toContain("not evidence of unchanged");
  });
});
