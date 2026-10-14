import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { comparisonSchema, type HistoryPoint, type Sample } from "./contracts";
import {
  changeLabel,
  forkMeans,
  histogram,
  historyGroups,
  latestComparisons,
  number,
} from "./presentation";
const capture = JSON.parse(
  readFileSync(
    new URL("../test-fixtures/synthetic-comparisons.json", import.meta.url),
    "utf8",
  ),
);
const comparison = comparisonSchema.parse(capture.comparisons[0]);
const sample = (value: number, index = 0): Sample => ({
  forkIndex: index,
  iterationIndex: 0,
  value,
});
describe("evidence presentation", () => {
  it("keeps accepted subnormal observations visible in fork means", () => {
    expect(
      forkMeans([
        { ...sample(Number.MIN_VALUE), iterationIndex: 0 },
        { ...sample(Number.MIN_VALUE), iterationIndex: 1 },
      ])[0].value,
    ).toBe(Number.MIN_VALUE);
  });
  it("labels worsening and improvement without implying significance", () => {
    expect(changeLabel(comparison)).toContain("worse");
    expect(
      changeLabel({
        ...comparison,
        summary: { ...comparison.summary!, degradationPercent: -3 },
      }),
    ).toBe("3% better");
    expect(
      changeLabel({
        ...comparison,
        summary: null,
        analysisStatus: "INCOMPATIBLE",
      }),
    ).toBe("Not comparable");
    expect(number(1e-200)).toBe("1.000e-200");
  });
  it("retains exact commit pairs while selecting only the latest stored version", () => {
    const old = {
      ...comparison,
      id: "old",
      analysisVersion: "fork-descriptive-v1",
      createdAt: "2026-01-01T00:00:00Z",
    };
    const differentPair = {
      ...old,
      id: "different",
      baselineRunId: "different-baseline",
    };
    expect(
      latestComparisons([old, comparison, differentPair]).map((c) => c.id),
    ).toEqual([comparison.id, "different"]);
  });
  it("never joins history across environment or configuration changes", () => {
    const point = (id: string, env: string, config: string, time: string) =>
      ({
        run: { id, environmentSha256: env, measuredAt: time },
        configurationSha256: config,
      }) as HistoryPoint;
    const groups = historyGroups([
      point("late", "a", "a", "2026-02-01"),
      point("early", "a", "a", "2026-01-01"),
      point("env", "b", "a", "2026-01-01"),
      point("config", "a", "b", "2026-01-01"),
    ]);
    expect(groups).toHaveLength(3);
    expect(groups[0].points.map((p) => p.run.id)).toEqual(["early", "late"]);
  });
  it("preserves counts at histogram boundaries, constant values, and tiny scales", () => {
    for (const [a, b] of [
      [[sample(1), sample(11)], [sample(5)]],
      [[sample(1)], [sample(1)]],
      [[sample(1e-200)], [sample(3e-200)]],
    ] as const) {
      const bins = histogram([...a], [...b]);
      expect(bins.reduce((sum, bin) => sum + bin.baseline, 0)).toBe(a.length);
      expect(bins.reduce((sum, bin) => sum + bin.candidate, 0)).toBe(b.length);
    }
    expect(histogram([], [])).toEqual([]);
  });
  it("summarizes each fork separately without treating iterations as independent", () => {
    expect(
      forkMeans([
        { ...sample(10), iterationIndex: 0 },
        { ...sample(20), iterationIndex: 1 },
        sample(30, 1),
      ]),
    ).toEqual([
      { fork: 0, value: 15, iterations: 2 },
      { fork: 1, value: 30, iterations: 1 },
    ]);
  });
});
