import type { BenchmarkCase, HistoryPoint } from "@/lib/contracts";
import { historyGroups } from "@/lib/presentation";
import { EmptyState, MetricTag } from "./ui";
import { TrendChart } from "./charts";
import { HistoryTable } from "./tables";
export function Trends({
  points,
  benchmark,
}: {
  points: HistoryPoint[];
  benchmark: BenchmarkCase;
}) {
  if (!points.length)
    return (
      <EmptyState title="No history for these conditions">
        Choose another benchmark or load a compatible run. No trend has been
        simulated.
      </EmptyState>
    );
  const groups = historyGroups(points);
  return (
    <div>
      <div className="trend-context">
        <MetricTag benchmark={benchmark} />
        <p>
          {groups.length > 1
            ? `${groups.length} condition groups; incompatible environments/configurations are shown separately.`
            : "Same recorded environment and configuration. Matching metadata does not establish controlled runtime conditions."}
        </p>
      </div>
      {groups.map((group) => (
        <div className="trend-group" key={group.key}>
          <div className="condition-label">
            <span>
              Environment{" "}
              <code>{group.points[0].run.environmentSha256.slice(0, 10)}</code>
            </span>
            <span>
              Configuration{" "}
              <code>{group.points[0].configurationSha256.slice(0, 10)}</code>
            </span>
          </div>
          {group.points.length >= 2 ? (
            <TrendChart points={group.points} unit={benchmark.unit} />
          ) : (
            <p className="single-point">
              One observation in this condition group. At least two runs are
              needed to show a time series.
            </p>
          )}
          <HistoryTable points={group.points} benchmark={benchmark} />
        </div>
      ))}
    </div>
  );
}
