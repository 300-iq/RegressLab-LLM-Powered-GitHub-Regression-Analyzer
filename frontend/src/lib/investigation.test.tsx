import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { analysisSchema, comparisonSchema, runDetailSchema } from "./contracts";
import { loadAnalysis, loadInvestigation, type Evidence, type AnalysisState } from "./investigation-data";
import { observedEffect, statusLabel, trustDescription, whatChanged } from "./investigation";
import { WhatChanged, AnalysisExplanation } from "@/components/results/investigation-workspace";
import { DistributionPair } from "@/components/results/investigation-charts";

// Captured executed measurements are test inputs only; no production fallback.
const capture = JSON.parse(readFileSync(new URL("../test-fixtures/github-pr1-history-results.json", import.meta.url), "utf8"));
const analysis = analysisSchema.parse(JSON.parse(readFileSync(new URL("../test-fixtures/pr1-bootstrap-analysis.json", import.meta.url), "utf8")));
const baseline = runDetailSchema.parse(capture.baseline), candidate = runDetailSchema.parse(capture.candidate);
const comparison = comparisonSchema.parse(capture.comparisons.find((c: {id: string}) => c.id === analysis.comparisonId));
const baselineBenchmark = baseline.benchmarks.find(b => b.benchmarkCase.id === comparison.benchmarkCaseId)!;
const candidateBenchmark = candidate.benchmarks.find(b => b.benchmarkCase.id === comparison.benchmarkCaseId)!;
function samples(arm: string) {
  return capture.audit.execution.blocks.filter((b: {arm: string}) => b.arm === arm).flatMap((b: {forkIndex: number; observations: {identifier: string; values: number[]}[]}) =>
    b.observations.find(o => o.identifier === baselineBenchmark.benchmarkCase.identifier)!.values.map((value, iterationIndex) => ({forkIndex: b.forkIndex, iterationIndex, value})));
}
function samplePage(arm: string, runId: string) {
  const values = samples(arm);
  return {unit:"ns/op" as const, values, runId, benchmarkCaseId: comparison.benchmarkCaseId, dataOrigin: "REAL" as const,
    samples: {items:values, page:0, size:100, totalElements:values.length}};
}
const evidence: Evidence = {comparison, baseline, candidate, baselineBenchmark, candidateBenchmark,
  benchmark: baselineBenchmark.benchmarkCase, baselineSamples: samplePage("BASELINE",baseline.run.id), candidateSamples: samplePage("CANDIDATE",candidate.run.id)};
const snapshots = {items: [analysis], page: 0, size: 20, totalElements: 1};
const ready: AnalysisState = {kind:"ready", analysis, snapshots};
afterEach(() => vi.unstubAllGlobals());
describe("deterministic investigation evidence", () => {
  it("shows the real large observation without promoting three forks to a regression verdict", () => {
    const facts = whatChanged(evidence, ready);
    expect(observedEffect(evidence, ready)).toBe(analysis.result.relativeChangePercent);
    expect(statusLabel(evidence, ready)).toBe("Inconclusive");
    expect(facts.find(f => f.id === "samples")?.body).toContain("3 baseline / 3 candidate");
    expect(facts.find(f => f.id === "threshold")?.body).toContain("±5%");
    expect(facts.find(f => f.id === "interval")?.body).toContain("not a guarantee");
    const html = renderToStaticMarkup(<><WhatChanged evidence={evidence} state={ready}/><AnalysisExplanation evidence={evidence} state={ready}/></>);
    expect(html).toContain("What changed?"); expect(html).toContain("Inconclusive");
    expect(html).toContain("97.5% nominal"); expect(html).toContain("10,000 resamples");
    expect(html).not.toContain("Regression indicated");
  });
  it("uses direction-adjusted change without calling lower throughput an improvement", () => {
    const higher = {...evidence, benchmark: {...evidence.benchmark, direction:"HIGHER" as const, unit:"ops/s" as const}};
    const changed = {...analysis, result:{...analysis.result, relativeChangePercent:10}};
    const facts = whatChanged(higher, {kind:"ready",analysis:changed,snapshots});
    expect(facts[0].title).toContain("decreased"); expect(facts[0].body).toContain("Higher is better");
    expect(facts[0].tone).toBe("warning");
    expect(whatChanged(evidence, {kind:"ready",analysis:{...changed,result:{...changed.result,relativeChangePercent:-10}},snapshots})[0].tone).toBe("positive");
  });
  it("describes equality at the threshold honestly and reports actual between-fork noise", () => {
    const changed = structuredClone(analysis); changed.result.relativeChangePercent = 5;
    changed.result.baseline!.forkMeans.coefficientOfVariation = .3;
    const facts = whatChanged(evidence, {kind:"ready",analysis:changed,snapshots});
    expect(facts.find(f => f.id === "threshold")?.title).toContain("touches");
    expect(facts.find(f => f.id === "variability")?.body).toContain("20%");
  });
  it("suppresses invalid effects and keeps missing/error states honest", () => {
    const invalid = {...analysis, result:{...analysis.result,classification:"INVALID_COMPARISON" as const,relativeChangePercent:null,interval:null}};
    expect(analysisSchema.safeParse(invalid).success).toBe(true);
    const state: AnalysisState = {kind:"ready",analysis:invalid,snapshots};
    expect(observedEffect(evidence,state)).toBeNull();
    expect(whatChanged(evidence,state).some(f => f.id === "effect" || f.id === "threshold")).toBe(false);
    const empty: AnalysisState = {kind:"empty",snapshots:{...snapshots,items:[],totalElements:0}};
    expect(statusLabel(evidence,empty)).toBe("Descriptive only");
    expect(renderToStaticMarkup(<AnalysisExplanation evidence={evidence} state={empty}/>)).toContain("No current bootstrap analysis stored");
    const error: AnalysisState = {kind:"error",message:"Unavailable"};
    expect(observedEffect(evidence,error)).toBeNull();
    expect(renderToStaticMarkup(<AnalysisExplanation evidence={evidence} state={error}/>)).toContain('role="alert"');
  });
  it.each(["confident", "units", "interval", "count", "invalid-effect"])("rejects a broken analysis contract: %s", mutation => {
    const a = structuredClone(analysis);
    if (mutation === "confident") a.result.classification = "REGRESSION_INDICATED";
    if (mutation === "units") a.result.candidate!.unit = "ops/s";
    if (mutation === "interval") a.result.interval!.lowerPercent = a.result.interval!.upperPercent + 1;
    if (mutation === "count") a.result.baseline!.forkMeans.count = 4;
    if (mutation === "invalid-effect") a.result.classification = "INVALID_COMPARISON";
    expect(analysisSchema.safeParse(a).success).toBe(false);
  });
  it("renders exact measured ranges, separate axes, and accessible distribution controls", () => {
    const html = renderToStaticMarkup(<DistributionPair baseline={evidence.baselineSamples.values} candidate={evidence.candidateSamples.values} unit="ns/op" baselineStats={<span>Baseline summary</span>} candidateStats={<span>Candidate summary</span>}/>);
    expect(html).toContain("Independent value axes"); expect(html).toContain('aria-label="Distribution view"');
    expect(html).toContain("Fork samples"); expect(html).toContain("15 raw observations in ns/op");
    expect(html).toContain(String(Math.min(...evidence.candidateSamples.values.map(s => s.value))));
    expect(html).toContain("Iterations within a fork are dependent");
  });
});
describe("read-only analysis discovery", () => {
  it("fetches paginated stored snapshots without tokens or analysis computation", async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json(snapshots)); vi.stubGlobal("fetch",fetcher);
    expect((await loadAnalysis(evidence,{})).kind).toBe("ready");
    expect(fetcher.mock.calls).toHaveLength(1);
    expect(fetcher.mock.calls[0][0]).toContain(`/comparisons/${comparison.id}/analyses?page=0&size=20`);
    expect(fetcher.mock.calls[0][1]).not.toHaveProperty("headers");
    expect(fetcher.mock.calls[0][1]).not.toHaveProperty("method");
  });
  it("supports explicit preserved snapshots but never substitutes v1 for the default v2", async () => {
    const old = {...analysis,analysisVersion:"fork-bootstrap-v1"};
    vi.stubGlobal("fetch",vi.fn().mockImplementation(() => Promise.resolve(Response.json({...snapshots,items:[old]}))));
    const defaultSelection = await loadAnalysis(evidence,{});
    expect(defaultSelection.kind).toBe("empty");
    expect(renderToStaticMarkup(<AnalysisExplanation evidence={evidence} state={defaultSelection}/>)).toContain("No v2 snapshot on this analysis page");
    const selected = await loadAnalysis(evidence,{analysis:old.id});
    expect(selected.kind === "ready" && selected.analysis.analysisVersion).toBe("fork-bootstrap-v1");
  });
  it("keeps raw evidence available on an outage and rejects foreign analysis selections", async () => {
    vi.stubGlobal("fetch",vi.fn().mockRejectedValue(new Error("secret")));
    const state = await loadAnalysis(evidence,{}); expect(state.kind).toBe("error");
    expect(JSON.stringify(state)).not.toContain("secret");
    const foreign = {...analysis, comparisonId:"ed9e03db-5fae-48c6-bdaa-0cd4b80d58a7"};
    vi.stubGlobal("fetch",vi.fn().mockResolvedValueOnce(Response.json({...snapshots,items:[]})).mockResolvedValueOnce(Response.json(foreign)));
    await expect(loadAnalysis(evidence,{analysis:foreign.id})).rejects.toMatchObject({kind:"missing"});
  });
  it("rejects analysis summaries that do not describe the loaded sample counts", async () => {
    const wrong = structuredClone(analysis); wrong.result.candidate!.rawMeasurements.count = 16;
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(Response.json({...snapshots,items:[wrong]})));
    expect((await loadAnalysis(evidence,{})).kind).toBe("error");
  });
});


describe("workspace review regressions", () => {
  it("never describes an interval for an invalid comparison with too few forks", () => {
    const invalid = {...analysis, result:{...analysis.result,classification:"INVALID_COMPARISON" as const,relativeChangePercent:null,interval:null}};
    const state: AnalysisState = {kind:"ready",analysis:invalid,snapshots};
    expect(trustDescription(state)).toContain("No relative effect or interval");
    expect(trustDescription(state)).not.toContain("exploratory");
    expect(renderToStaticMarkup(<AnalysisExplanation state={state} evidence={evidence}/>)).not.toContain("Conditional interval");
  });
  it.each(["baseline", "effect", "interval", "identity"])("rejects incomplete non-invalid analysis evidence: %s", field => {
    const a = structuredClone(analysis);
    if (field === "baseline") a.result.baseline = null;
    if (field === "effect") a.result.relativeChangePercent = null;
    if (field === "interval") a.result.interval = null;
    if (field === "identity") a.result.benchmarkIdentifier = null;
    expect(analysisSchema.safeParse(a).success).toBe(false);
  });

  // Captures used only as isolated fetch-test inputs, never browser interception.
  const apiCapture = JSON.parse(readFileSync(new URL("../test-fixtures/pr1-workspace-history.json",import.meta.url),"utf8"));
  const original = JSON.parse(readFileSync(new URL("../test-fixtures/github-pr1-results.json",import.meta.url),"utf8"));
  function apiWithFailure(failure: "history" | "sample-unit" | "sample-origin" | "sample-count" | "none") {
    return vi.fn(async (url: string) => {
      const path = new URL(url).pathname.replace("/api/v1","").toLowerCase();
      if (path === "/repositories") return Response.json([apiCapture.repository]);
      if (path === `/pull-requests/${apiCapture.detail.pullRequest.id}`) return Response.json(apiCapture.detail);
      if (path.endsWith("/history")) return failure === "history" ? new Response(null,{status:503}) : Response.json(apiCapture.histories[0]);
      if (path.endsWith("/analyses")) {
        const id = path.split("/")[2];
        const page = apiCapture.analyses.find((p: {items:{comparisonId:string}[]}) => p.items[0]?.comparisonId === id);
        return Response.json(page ?? {items:[],page:0,size:20,totalElements:0});
      }
      const sample = apiCapture.runSamples.find((s:{runId:string;benchmarkCaseId:string}) => path === `/runs/${s.runId}/cases/${s.benchmarkCaseId}/samples`);
      if(sample) {
        if (failure === "sample-unit") return Response.json({...sample,unit: sample.unit === "ns/op" ? "ops/s" : "ns/op"});
        if (failure === "sample-origin") return Response.json({...sample,dataOrigin:"DEMO"});
        if (failure === "sample-count") return Response.json({...sample,samples:{...sample.samples,items:sample.samples.items.slice(0,-1),totalElements:sample.samples.totalElements-1}});
        return Response.json(sample);
      }
      for(const arm of [capture.baseline,capture.candidate,original.baseline,original.candidate]) if(path === `/runs/${arm.run.id}`) return Response.json(arm);
      throw new Error(`Unexpected fetch test path ${path}`);
    });
  }
  it("retains current evidence when historical reads fail", async () => {
    vi.stubGlobal("fetch",apiWithFailure("history"));
    const data = await loadInvestigation(apiCapture.detail.pullRequest.id,{});
    expect(data.evidence?.baselineSamples.values).toHaveLength(15);
    expect(data.history).toBeNull();
    expect(data).toHaveProperty("historyError", "Historical measurements could not be loaded. Current comparison evidence remains available.");
  });
  it("accepts the uppercase spelling of the same UUID accepted by the backend", async () => {
    vi.stubGlobal("fetch",apiWithFailure("none"));
    const data = await loadInvestigation(apiCapture.detail.pullRequest.id.toUpperCase(),{});
    expect(data.pr.id).toBe(apiCapture.detail.pullRequest.id);
    expect(data.evidence).toBeTruthy();
  });
  it.each(["sample-unit", "sample-origin", "sample-count"] as const)("rejects run-only sample evidence mismatch: %s", async failure => {
    vi.stubGlobal("fetch",apiWithFailure(failure));
    await expect(loadInvestigation(apiCapture.detail.pullRequest.id,{run:baseline.run.id,case:baselineBenchmark.benchmarkCase.id})).rejects.toMatchObject({kind:"invalid"});
  });
});

describe("histogram count-axis review", () => {
  it("positions integer labels on the same linear scale as the measured bin counts", () => {
    const html = renderToStaticMarkup(<DistributionPair baseline={evidence.baselineSamples.values} candidate={evidence.candidateSamples.values} unit="ns/op" baselineStats={null} candidateStats={null}/>);
    const axis = [...html.matchAll(/<text x="33" y="([^"]+)" text-anchor="end">(\d+)<\/text>/g)].slice(0,3).map(match => ({y:Number(match[1]),count:Number(match[2])}));
    const [zero,middle,maximum] = axis;
    expect(zero.count).toBe(0);
    // Equal SVG text offsets cancel: labeled count and drawn position must agree.
    expect((zero.y-middle.y)/(zero.y-maximum.y)).toBeCloseTo(middle.count/maximum.count,12);
  });
});
