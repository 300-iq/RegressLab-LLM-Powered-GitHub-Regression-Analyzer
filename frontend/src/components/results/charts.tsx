"use client";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { HistoryPoint, Sample } from "@/lib/contracts";
import { histogram, number, shortSha, timestamp } from "@/lib/presentation";
const baselineColor = "#27857f",
  candidateColor = "#7560d4";
export function DistributionChart({
  baseline,
  candidate,
  unit,
  baselineLabel = "Baseline iterations",
}: {
  baseline: Sample[];
  candidate: Sample[];
  unit: string;
  baselineLabel?: string;
}) {
  const data = histogram(baseline, candidate);
  return (
    <figure className="chart-figure">
      <div
        className="chart-frame"
        role="img"
        aria-label={`Histogram of ${baseline.length} ${baselineLabel.toLowerCase()}${candidate.length ? ` and ${candidate.length} candidate raw iterations` : ""} in ${unit}. Exact observations are available in the raw sample table.`}
      >
        <ResponsiveContainer width="100%" height={270} minWidth={0}>
          <BarChart
            data={data}
            accessibilityLayer
            margin={{ top: 12, right: 12, left: 0, bottom: 10 }}
          >
            <CartesianGrid vertical={false} stroke="#edf0f5" />
            <XAxis
              dataKey="range"
              tick={{ fontSize: 9 }}
              interval="preserveStartEnd"
            />
            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} width={35} />
            <Tooltip labelFormatter={(label) => `${label} ${unit}`} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Bar
              dataKey="baseline"
              name={baselineLabel}
              fill={baselineColor}
              radius={[3, 3, 0, 0]}
            />
            {candidate.length > 0 && (
              <Bar
                dataKey="candidate"
                name="Candidate iterations"
                fill={candidateColor}
                radius={[3, 3, 0, 0]}
              />
            )}
          </BarChart>
        </ResponsiveContainer>
      </div>
      <figcaption>
        Value ranges ({unit}) · height is iteration count. Iterations within a
        fork are correlated; this distribution is descriptive.
      </figcaption>
    </figure>
  );
}
export function TrendChart({
  points,
  unit,
}: {
  points: HistoryPoint[];
  unit: string;
}) {
  const data = points.map((point) => ({
    measuredAt: Date.parse(point.run.measuredAt),
    mean: point.mean,
    commit: shortSha(point.run.commitSha),
    runId: point.run.id,
  }));
  return (
    <figure className="chart-figure">
      <div
        className="chart-frame"
        role="img"
        aria-label={`Time series of ${points.length} run means in ${unit}, with matching environment and configuration. Values are listed in the history table.`}
      >
        <ResponsiveContainer width="100%" height={230} minWidth={0}>
          <LineChart
            data={data}
            accessibilityLayer
            margin={{ top: 15, right: 18, left: 5, bottom: 5 }}
          >
            <CartesianGrid vertical={false} stroke="#edf0f5" />
            <XAxis
              dataKey="measuredAt"
              type="number"
              domain={["dataMin", "dataMax"]}
              tick={{ fontSize: 10 }}
              tickFormatter={(value) =>
                timestamp(new Date(value).toISOString()).replace(" UTC", "")
              }
              minTickGap={40}
            />
            <YAxis
              domain={["auto", "auto"]}
              tickFormatter={number}
              tick={{ fontSize: 10 }}
              width={65}
            />
            <Tooltip
              labelFormatter={(value) =>
                timestamp(new Date(Number(value)).toISOString())
              }
              formatter={(value) => [
                `${number(Number(value))} ${unit}`,
                "Mean of fork means",
              ]}
            />
            <Line
              dataKey="mean"
              type="linear"
              stroke={candidateColor}
              strokeWidth={2}
              dot={{
                r: 4,
                fill: candidateColor,
                stroke: "white",
                strokeWidth: 2,
              }}
              activeDot={{ r: 6 }}
              connectNulls={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <figcaption>
        Mean of JVM fork means · {unit}. Connecting observations does not
        establish a causal trend or statistical confidence.
      </figcaption>
    </figure>
  );
}
