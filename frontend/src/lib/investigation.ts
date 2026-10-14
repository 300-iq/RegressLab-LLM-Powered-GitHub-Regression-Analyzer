import type { Analysis, Classification } from "./contracts";
import type { AnalysisState, Evidence } from "./investigation-data";
import { number } from "./presentation";
export const classificationLabel: Record<Classification, string> = {
  REGRESSION_INDICATED: "Regression indicated", IMPROVEMENT_INDICATED: "Improvement indicated",
  NO_MEANINGFUL_CHANGE_DETECTED: "No meaningful change detected", INCONCLUSIVE: "Inconclusive", INVALID_COMPARISON: "Invalid comparison",
};
export function observedEffect(evidence: Evidence, state: AnalysisState) {
  if (state.kind === "error") return null;
  if (state.kind === "ready") return state.analysis.result.relativeChangePercent;
  return evidence.comparison.summary?.degradationPercent ?? null;
}
export function statusLabel(evidence: Evidence, state: AnalysisState) {
  return state.kind === "ready" ? classificationLabel[state.analysis.result.classification] : state.kind === "error" ? "Analysis unavailable" :
    evidence.comparison.analysisStatus === "INCOMPATIBLE" ? "Invalid comparison" : "Descriptive only";
}
export type Finding = { id: string; title: string; body: string; tone: "neutral" | "warning" | "positive" };
// Pure deterministic presentation of persisted facts, not a second classifier.
export function whatChanged(evidence: Evidence, state: AnalysisState): Finding[] {
  const findings: Finding[] = [], a: Analysis | null = state.kind === "ready" ? state.analysis : null;
  const effect = observedEffect(evidence, state);
  if (effect !== null) {
    const rawChange = evidence.benchmark.direction === "LOWER" ? effect : -effect;
    const statistic = a ? "median of JVM-fork means" : "mean of JVM-fork means";
    findings.push({ id: "effect", title: `Candidate ${statistic} ${rawChange > 0 ? "increased" : rawChange < 0 ? "decreased" : "was unchanged"}`,
      body: `${number(Math.abs(rawChange))}% observed change in ${evidence.benchmark.unit}. ${evidence.benchmark.direction === "LOWER" ? "Lower" : "Higher"} is better. This observation alone does not establish a regression.`, tone: effect > 0 ? "warning" : effect < 0 ? "positive" : "neutral" });
  }
  if (a) {
    const threshold = a.policy.practicalThresholdPercent;
    if (effect !== null) findings.push({ id: "threshold", title: Math.abs(effect) > threshold ? "Observed change exceeds the practical threshold" : Math.abs(effect) === threshold ? "Observed change touches the practical threshold" : "Observed change is inside the practical band",
      body: `The stored threshold is ±${number(threshold)}%. Classification also depends on the interval, sample adequacy, noise and compatibility checks.`, tone: effect > threshold ? "warning" : "neutral" });
    const b = a.result.baseline, c = a.result.candidate;
    if (b && c && (b.forkMeans.count < a.policy.minimumForksPerArm || c.forkMeans.count < a.policy.minimumForksPerArm))
      findings.push({ id: "samples", title: "Too few forks for a confident classification", body: `${b.forkMeans.count} baseline / ${c.forkMeans.count} candidate JVM forks; the policy requires ${a.policy.minimumForksPerArm} per arm. Repeated iterations do not increase that count.`, tone: "warning" });
    if (b && c && ((b.forkMeans.coefficientOfVariation ?? 0) > a.policy.maximumForkCv || (c.forkMeans.coefficientOfVariation ?? 0) > a.policy.maximumForkCv))
      findings.push({ id: "variability", title: "Measurements show substantial variability", body: `Between-fork CV exceeds the recorded ${number(a.policy.maximumForkCv * 100)}% noise guard in at least one arm.`, tone: "warning" });
    if (a.result.classification === "INVALID_COMPARISON") findings.push({ id: "invalid", title: "These measurements cannot support a comparison", body: a.result.explanation, tone: "warning" });
    if (a.result.interval?.exploratory) findings.push({ id: "interval", title: "The interval is exploratory", body: "The resampling output cannot override failed adequacy or noise checks. Its nominal level is not a guarantee of coverage.", tone: "warning" });
  } else findings.push({ id: "analysis", title: state.kind === "error" ? "Classification could not be loaded" : state.snapshots.totalElements ? "No v2 snapshot on this analysis page" : "No current bootstrap analysis stored", body: state.kind === "error" ? state.message : "No supported v2 snapshot is selected. The descriptive observation is shown without substituting a threshold, interval or inferential verdict.", tone: "warning" });
  findings.push({ id: "conditions", title: evidence.baseline.run.environmentSha256 === evidence.candidate.run.environmentSha256 && evidence.baselineBenchmark.configurationSha256 === evidence.candidateBenchmark.configurationSha256 ? "Recorded test conditions match" : "Recorded test conditions differ",
    body: "Inspect runner, CPU, JVM and configuration metadata. Matching metadata cannot rule out shared-runner contention or temporal drift.", tone: evidence.comparison.analysisStatus === "INCOMPATIBLE" ? "warning" : "neutral" });
  return findings;
}

// The header uses the same stored analysis state as the detailed evidence panel.
export function trustDescription(state: AnalysisState) {
  const a = state.kind === "ready" ? state.analysis : null;
  if (a?.result.classification === "INVALID_COMPARISON") return "No relative effect or interval was calculated; comparability or measurement checks failed.";
  return a && a.result.baseline && a.result.candidate &&
    (a.result.baseline.forkMeans.count < a.policy.minimumForksPerArm || a.result.candidate.forkMeans.count < a.policy.minimumForksPerArm)
    ? `Too few forks: policy requires ${a.policy.minimumForksPerArm} per arm. Interval is exploratory.`
    : a?.result.interval?.exploratory ? "Exploratory interval · sample adequacy rules still apply."
    : a ? "Conditional evidence; no proof of causation or significance." : "No inferential verdict is available.";
}
