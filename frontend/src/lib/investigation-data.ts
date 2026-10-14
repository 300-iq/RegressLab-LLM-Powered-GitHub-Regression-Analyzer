import type { Analysis, Comparison, HistoryPoint, Page } from "./contracts";
import { latestComparisons } from "./presentation";
import { ApiError, getAnalyses, getAnalysis, getComparison, getHistory, getPr, getPrComparisons,
  getRepository, getRun, getAllSamples, attempt, pageNumber, queryValue, type Query } from "./results";
import { comparisonEvidence } from "./workspace";

export type AnalysisState = { kind: "ready"; analysis: Analysis; snapshots: Page<Analysis> } |
  { kind: "empty"; snapshots: Page<Analysis> } | { kind: "error"; message: string };
export type Evidence = Awaited<ReturnType<typeof comparisonEvidence>>;
export async function loadAnalysis(evidence: Evidence, query: Query): Promise<AnalysisState> {
  try {
    const snapshots = await getAnalyses(evidence.comparison.id, pageNumber(query, "snapshotsPage"));
    if (snapshots.items.some(item => item.comparisonId !== evidence.comparison.id)) throw new ApiError("invalid");
    const requested = queryValue(query, "analysis");
    const analysis = requested ? snapshots.items.find(item => item.id === requested) ?? await getAnalysis(requested) :
      snapshots.items.find(item => item.analysisVersion === "fork-bootstrap-v2");
    for (const item of analysis ? [analysis] : []) {
      const r = item.result;
      if (item.comparisonId !== evidence.comparison.id) throw new ApiError("missing");
      if (item.dataOrigin !== evidence.comparison.dataOrigin ||
          (r.benchmarkIdentifier !== null && r.benchmarkIdentifier !== evidence.benchmark.identifier) ||
          (r.unit !== null && r.unit !== evidence.benchmark.unit) ||
          (r.direction !== null && r.direction !== evidence.benchmark.direction) ||
          (r.baseline && (r.baseline.rawMeasurements.count !== evidence.baselineSamples.values.length || r.baseline.forkMeans.count !== evidence.baselineBenchmark.configuration.forkCount)) ||
          (r.candidate && (r.candidate.rawMeasurements.count !== evidence.candidateSamples.values.length || r.candidate.forkMeans.count !== evidence.candidateBenchmark.configuration.forkCount)))
        throw new ApiError("invalid");
    }
    return analysis ? { kind: "ready", analysis, snapshots } : { kind: "empty", snapshots };
  } catch (error) {
    if (error instanceof ApiError && error.kind === "missing" && queryValue(query, "analysis")) throw error;
    return { kind: "error", message: error instanceof ApiError && error.kind === "invalid" ?
      "The stored analysis did not match the supported evidence contract." : "Stored analysis is temporarily unavailable. Raw measurements remain inspectable." };
  }
}
export async function loadInvestigation(id: string, query: Query) {
  const detail = await getPr(id, pageNumber(query, "runsPage"));
  const pr = detail.pullRequest;
  if (pr.id !== id.toLowerCase()) throw new ApiError("invalid");
  const repo = await getRepository(pr.repositoryId);
  const comparisons = pageNumber(query, "analysesPage") === detail.comparisons.page ? detail.comparisons :
    await getPrComparisons(pr.id, pageNumber(query, "analysesPage"));
  const runId = queryValue(query, "run"), selectedId = queryValue(query, "comparison");
  const current = latestComparisons(comparisons.items).find(item => {
    const b = detail.runs.items.find(run => run.id === item.baselineRunId), c = detail.runs.items.find(run => run.id === item.candidateRunId);
    return b?.commitSha === pr.baselineSha && c?.commitSha === pr.candidateSha;
  });
  const comparison: Comparison | undefined = selectedId ? await getComparison(selectedId) : runId ? undefined : current ?? latestComparisons(comparisons.items)[0];
  const evidence = comparison ? await comparisonEvidence(comparison) : null;
  if (evidence && (evidence.baseline.run.pullRequestId !== pr.id || evidence.candidate.run.pullRequestId !== pr.id || comparison?.repositoryId !== pr.repositoryId)) throw new ApiError("missing");
  const inspected = !evidence && (runId || detail.runs.items[0]?.id) ? await getRun(runId ?? detail.runs.items[0].id) : null;
  if (inspected && inspected.run.pullRequestId !== pr.id) throw new ApiError("missing");
  const catalog = evidence ? evidence.baseline.benchmarks.map(b => b.benchmarkCase) : inspected?.benchmarks.map(b => b.benchmarkCase) ?? [];
  const selectedCase = queryValue(query, "case")?.toLowerCase();
  const singleBenchmark = inspected?.benchmarks.find(b => selectedCase ? b.benchmarkCase.id === selectedCase : true);
  const singleSamples = inspected && singleBenchmark ? await getAllSamples(inspected.run.id, singleBenchmark.benchmarkCase.id) : null;
  if (singleSamples && singleBenchmark && inspected && (singleSamples.unit !== singleBenchmark.benchmarkCase.unit ||
      singleSamples.dataOrigin !== inspected.run.dataOrigin || singleSamples.values.length !== singleBenchmark.sampleCount)) throw new ApiError("invalid");
  const outdated = !!evidence && (evidence.baseline.run.commitSha !== pr.baselineSha || evidence.candidate.run.commitSha !== pr.candidateSha);
  let analysisState: AnalysisState | null = null, history: Page<HistoryPoint> | null = null, historyError: string | null = null;
  if (evidence) {
    const [analysis, historical] = await Promise.all([
      loadAnalysis(evidence, query), attempt(async () => {
        const page = await getHistory(evidence.benchmark.id, repo, pageNumber(query, "historyPage"));
        if (page.items.some(point => point.run.repositoryId !== repo.id || point.run.dataOrigin !== repo.dataOrigin)) throw new ApiError("invalid");
        return page;
      }),
    ]);
    analysisState = analysis;
    if (historical.kind === "ready") history = historical.value;
    else historyError = historical.error instanceof ApiError && historical.error.kind === "invalid"
      ? "Historical measurements failed validation. Current comparison evidence remains available."
      : "Historical measurements could not be loaded. Current comparison evidence remains available.";
  }
  return { id: pr.id, query, detail, pr, repo, path: `/investigations/${pr.id}`, comparisons, comparison, evidence,
    inspected, catalog, selectedCase, singleBenchmark, singleSamples, outdated, analysisState, history, historyError };
}
export type InvestigationData = Awaited<ReturnType<typeof loadInvestigation>>;
