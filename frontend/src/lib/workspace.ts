import { caseCatalog } from "./presentation";
import {
  ApiError,
  getAllSamples,
  getPrComparisons,
  getPrs,
  getRun,
  getRuns,
} from "./results";
import type { Comparison, Repository } from "./contracts";
export async function repositoryWorkspace(
  repo: Repository,
  prPage = 0,
  runPage = 0,
  size = 20,
) {
  const [prs, runs] = await Promise.all([
    getPrs(repo, prPage, size),
    getRuns(repo, runPage, size),
  ]);
  const details = await Promise.all(runs.items.map((run) => getRun(run.id)));
  return { prs, runs, details, cases: caseCatalog(details) };
}
export async function recentChanges(repo: Repository) {
  const [prs, runs] = await Promise.all([
    getPrs(repo, 0, 5),
    getRuns(repo, 0, 6),
  ]);
  const [details, comparisons] = await Promise.all([
    Promise.all(runs.items.map((run) => getRun(run.id))),
    Promise.all(
      prs.items.map(async (pr) => ({
        pr,
        analyses: await getPrComparisons(pr.id),
      })),
    ),
  ]);
  return { prs, runs, details, cases: caseCatalog(details), comparisons };
}
export async function comparisonEvidence(comparison: Comparison) {
  const [baseline, candidate] = await Promise.all([
    getRun(comparison.baselineRunId),
    getRun(comparison.candidateRunId),
  ]);
  const baselineBenchmark = baseline.benchmarks.find(
    (b) => b.benchmarkCase.id === comparison.benchmarkCaseId,
  );
  const candidateBenchmark = candidate.benchmarks.find(
    (b) => b.benchmarkCase.id === comparison.benchmarkCaseId,
  );
  if (
    !baselineBenchmark ||
    !candidateBenchmark ||
    baseline.run.id !== comparison.baselineRunId ||
    candidate.run.id !== comparison.candidateRunId ||
    baseline.run.repositoryId !== comparison.repositoryId ||
    candidate.run.repositoryId !== comparison.repositoryId ||
    baseline.run.pullRequestId !== candidate.run.pullRequestId ||
    baseline.run.dataOrigin !== comparison.dataOrigin ||
    candidate.run.dataOrigin !== comparison.dataOrigin ||
    baselineBenchmark.benchmarkCase.identifier !==
      candidateBenchmark.benchmarkCase.identifier ||
    baselineBenchmark.benchmarkCase.unit !==
      candidateBenchmark.benchmarkCase.unit ||
    baselineBenchmark.benchmarkCase.direction !==
      candidateBenchmark.benchmarkCase.direction
  )
    throw new ApiError("invalid");
  const [baselineSamples, candidateSamples] = await Promise.all([
    getAllSamples(baseline.run.id, comparison.benchmarkCaseId),
    getAllSamples(candidate.run.id, comparison.benchmarkCaseId),
  ]);
  if (
    baselineSamples.dataOrigin !== comparison.dataOrigin ||
    candidateSamples.dataOrigin !== comparison.dataOrigin ||
    baselineSamples.unit !== baselineBenchmark.benchmarkCase.unit ||
    candidateSamples.unit !== baselineSamples.unit ||
    baselineSamples.values.length !== baselineBenchmark.sampleCount ||
    candidateSamples.values.length !== candidateBenchmark.sampleCount
  )
    throw new ApiError("invalid");
  return {
    comparison,
    baseline,
    candidate,
    baselineBenchmark,
    candidateBenchmark,
    baselineSamples,
    candidateSamples,
    benchmark: baselineBenchmark.benchmarkCase,
  };
}
