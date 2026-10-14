import type { Comparison, HistoryPoint, RunDetail, Sample } from "./contracts";
export function number(value: number) {
  return value !== 0 && (Math.abs(value) < 0.001 || Math.abs(value) >= 1e9)
    ? value.toExponential(3)
    : value.toLocaleString("en-US", { maximumFractionDigits: 3 });
}
export const timestamp = (value: string) =>
  new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "UTC",
  }).format(new Date(value)) + " UTC";
export const shortSha = (value: string) => value.slice(0, 8);
export const direction = (value: "LOWER" | "HIGHER") =>
  value === "LOWER" ? "Lower is better" : "Higher is better";
export function changeLabel(comparison: Comparison) {
  if (!comparison.summary) return "Not comparable";
  const value = comparison.summary.degradationPercent;
  if (value === 0) return "No observed change";
  return `${number(Math.abs(value))}% ${value > 0 ? "worse" : "better"}`;
}
export const reasonLabel = (reason: string) =>
  ({
    ARTIFACT_MEASUREMENTS_UNTRUSTED: "GitHub artifact origin is checked. PR code can influence measurement claims; origin checks are not measurement attestation.",
  LOCAL_PROVENANCE_UNVERIFIED: "GitHub provenance has not been verified.",
    INFERENTIAL_ANALYSIS_NOT_IMPLEMENTED:
      "No confidence interval or significance test has been calculated.",
    FEWER_THAN_10_INDEPENDENT_FORKS:
      "Fewer than 10 JVM forks in at least one arm.",
    ENVIRONMENT_MISMATCH: "Execution environments differ.",
    CONFIGURATION_MISMATCH: "Benchmark configurations or parameters differ.",
  })[reason] ?? reason.replaceAll("_", " ").toLowerCase();
// Retained versions remain selectable, but recent-change lists show only the newest
// stored analysis per exact run pair/case. Never merge different commit pairs.
export function latestComparisons(items: Comparison[]) {
  const seen = new Set<string>();
  return [...items]
    .sort(
      (a, b) =>
        b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id),
    )
    .filter((item) => {
      const key = `${item.baselineRunId}:${item.candidateRunId}:${item.benchmarkCaseId}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}
export function caseCatalog(details: RunDetail[]) {
  const cases = new Map(
    details.flatMap((detail) =>
      detail.benchmarks.map(
        (b) => [b.benchmarkCase.id, b.benchmarkCase] as const,
      ),
    ),
  );
  return [...cases.values()].sort((a, b) =>
    a.identifier.localeCompare(b.identifier),
  );
}
export function historyGroups(points: HistoryPoint[]) {
  const groups = new Map<string, HistoryPoint[]>();
  for (const point of points) {
    const key = `${point.run.environmentSha256}:${point.configurationSha256}`;
    groups.set(key, [...(groups.get(key) ?? []), point]);
  }
  return [...groups.entries()].map(([key, values]) => ({
    key,
    points: values.sort(
      (a, b) =>
        a.run.measuredAt.localeCompare(b.run.measuredAt) ||
        a.run.id.localeCompare(b.run.id),
    ),
  }));
}
export function forkMeans(samples: Sample[]) {
  const forks = new Map<number, number[]>();
  for (const sample of samples)
    forks.set(sample.forkIndex, [
      ...(forks.get(sample.forkIndex) ?? []),
      sample.value,
    ]);
  return [...forks.entries()]
    .sort(([a], [b]) => a - b)
    .map(([fork, values]) => ({
      fork,
      value: values.reduce((sum, value) => sum + value, 0) / values.length,
      iterations: values.length,
    }));
}
export function histogram(baseline: Sample[], candidate: Sample[], bins = 10) {
  const values = [...baseline, ...candidate].map((sample) => sample.value);
  if (!values.length) return [];
  const minimum = Math.min(...values),
    maximum = Math.max(...values);
  const span = maximum - minimum;
  const result = Array.from({ length: span === 0 ? 1 : bins }, (_, i) => {
    const start = minimum + (span * i) / bins,
      end = minimum + (span * (i + 1)) / bins;
    return {
      range: span === 0 ? number(minimum) : `${number(start)}–${number(end)}`,
      baseline: 0,
      candidate: 0,
    };
  });
  for (const [side, samples] of [
    ["baseline", baseline],
    ["candidate", candidate],
  ] as const) {
    for (const sample of samples)
      result[
        span === 0
          ? 0
          : Math.min(
              bins - 1,
              Math.floor(((sample.value - minimum) / span) * bins),
            )
      ][side]++;
  }
  return result;
}
