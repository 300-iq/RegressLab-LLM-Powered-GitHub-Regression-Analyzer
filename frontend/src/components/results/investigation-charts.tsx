"use client";
import { useId, useState } from "react";
import type { HistoryPoint, Repository, Sample } from "@/lib/contracts";
import { number, shortSha, timestamp } from "@/lib/presentation";

function SamplePlot({ samples, unit, arm, mode, median }: { samples: Sample[]; unit: string; arm: string; mode: "histogram" | "forks"; median?: number }) {
  const title = useId();
  if (!samples.length || samples.some(s => s.value < 2.2250738585072014e-308))
    return <p className="inv-plot-empty">No plottable measurements at a supported numerical scale. Inspect the raw observations.</p>;
  const min = Math.min(...samples.map(s => s.value)), max = Math.max(...samples.map(s => s.value));
  const span = max - min, width = 420, height = 156, left = 42, right = 400, top = 16, bottom = 117;
  const x = (v: number) => span === 0 ? (left + right) / 2 : left + ((v - min) / span) * (right - left);
  const bins = Array.from({ length: span === 0 ? 1 : 6 }, () => 0);
  for (const sample of samples) bins[span === 0 ? 0 : Math.min(5, Math.floor((sample.value - min) / span * 6))]++;
  const maxCount = Math.max(...bins), forks = [...new Set(samples.map(s => s.forkIndex))].sort((a, b) => a - b);
  const ticks = mode === "histogram" ? [...new Set([0, Math.ceil(maxCount / 2), maxCount])].map(count => ({label:count, fraction:count / maxCount})) : [0, .5, 1].map(fraction => ({label:fraction, fraction}));
  return <figure className={`inv-distribution ${arm.toLowerCase()}`}>
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-labelledby={title}>
      <title id={title}>{`${arm}: ${samples.length} raw observations in ${unit}. ${mode === "histogram" ? "Histogram; height is iteration count." : "Samples grouped by JVM fork."} Value axis ${String(min)} to ${String(max)}. Exact measurements are in the raw table.`}</title>
      {ticks.map(t => <g key={t.fraction}><line x1={left} x2={right} y1={bottom - t.fraction * (bottom - top)} y2={bottom - t.fraction * (bottom - top)} className="inv-grid-line" />
        {mode === "histogram" && <text x={left - 9} y={bottom - t.fraction * (bottom - top) + 4} textAnchor="end">{t.label}</text>}</g>)}
      {mode === "histogram" ? bins.map((count, i) => {
        const binWidth = (right - left) / bins.length, h = count / maxCount * (bottom - top);
        return <rect key={i} x={left + i * binWidth + 3} y={bottom - h} width={binWidth - 6} height={h} rx="3" className="inv-bar"><title>{`${count} iterations in value bin ${i + 1}`}</title></rect>;
      }) : <>
        {forks.map((fork, i) => <text key={fork} x={left - 8} y={top + (i + .5) * (bottom - top) / forks.length + 4} textAnchor="end">F{fork}</text>)}
        {samples.map(s => <circle key={`${s.forkIndex}:${s.iterationIndex}`} cx={x(s.value)} cy={top + (forks.indexOf(s.forkIndex) + .5) * (bottom - top) / forks.length + (s.iterationIndex % 3 - 1) * 7} r="4" className="inv-dot"><title>{`Fork ${s.forkIndex}, iteration ${s.iterationIndex}: ${String(s.value)} ${unit}`}</title></circle>)}
      </>}
      {median !== undefined && <line x1={x(median)} x2={x(median)} y1={top} y2={bottom} className="inv-median-line"><title>{`Raw median: ${String(median)} ${unit}`}</title></line>}
      <line x1={left} x2={right} y1={bottom} y2={bottom} className="inv-axis-line" />
      <text x={left} y={bottom + 20}>{number(min)}</text><text x={right} y={bottom + 20} textAnchor="end">{number(max)}</text>
      <text x={(left + right) / 2} y={height - 2} textAnchor="middle">Measured value · {unit}</text>
    </svg>
    <figcaption>{mode === "histogram" ? "Height = iteration count" : "F = JVM fork; vertical offsets separate iterations"}{median !== undefined ? ` · dashed line = raw median ${number(median)} ${unit}` : ""}</figcaption>
  </figure>;
}
export function DistributionPair({ baseline, candidate, unit, baselineMedian, candidateMedian, baselineStats, candidateStats }: {
  baseline: Sample[]; candidate: Sample[]; unit: string; baselineMedian?: number; candidateMedian?: number;
  baselineStats: React.ReactNode; candidateStats: React.ReactNode;
}) {
  const [mode, setMode] = useState<"histogram" | "forks">("histogram");
  return <div className="inv-measurements">
    <div className="inv-measurement-tools"><p>Independent value axes · compare the numbers, not the chart widths.</p>
      <div className="inv-segment" role="group" aria-label="Distribution view">
        <button type="button" aria-pressed={mode === "histogram"} onClick={() => setMode("histogram")}>Histogram</button>
        <button type="button" aria-pressed={mode === "forks"} onClick={() => setMode("forks")}>Fork samples</button>
      </div>
    </div>
    <div className="inv-arm-grid">{([
      { arm: "Baseline", samples: baseline, median: baselineMedian, stats: baselineStats },
      { arm: "Candidate", samples: candidate, median: candidateMedian, stats: candidateStats },
    ]).map(a => <article key={a.arm} className={`inv-arm ${a.arm.toLowerCase()}`} aria-label={`${a.arm} distribution`}>
      <div className="inv-arm-heading"><h3><span className="inv-swatch" />{a.arm}</h3><span>{a.samples.length} iterations · {unit}</span></div>
      {a.stats}<SamplePlot samples={a.samples} median={a.median} unit={unit} arm={a.arm} mode={mode} />
    </article>)}</div>
    <p className="inv-chart-note">Every stored measured iteration is shown. Warmup observations are excluded. Iterations within a fork are dependent.</p>
  </div>;
}

export function CommitHistoryPlot({ points, repo, selectedRuns, currentShas, unit }: { points: HistoryPoint[]; repo: Repository;
  selectedRuns: string[]; currentShas: string[]; unit: string }) {
  const label = useId(), width = 660, left = 80, right = 630, top = 20, bottom = 155;
  const values = points.map(p => p.mean), min = Math.min(...values), max = Math.max(...values);
  if (!points.length) return null;
  if (values.some(v => v < 2.2250738585072014e-308)) return <p className="inv-plot-empty">History values are outside the supported chart scale. Exact values remain in the table.</p>;
  const x = (i: number) => points.length === 1 ? (left + right) / 2 : left + i * (right - left) / (points.length - 1);
  const y = (value: number) => max === min ? (top + bottom) / 2 : bottom - (value - min) / (max - min) * (bottom - top);
  return <figure className="inv-history-plot">
    <svg viewBox={`0 0 ${width} 203`} role="img" aria-labelledby={label}>
      <title id={label}>{`History of ${points.length} compatible run means in ${unit}. Commit links and exact values follow the chart.`}</title>
      {(min === max ? [min] : [min, min + (max - min) / 2, max]).map((v, i) => <g key={i}><line x1={left} x2={right} y1={y(v)} y2={y(v)} className="inv-grid-line" /><text x={left - 10} y={y(v) + 4} textAnchor="end">{number(v)}</text></g>)}
      {points.length > 1 && <polyline points={points.map((p, i) => `${x(i)},${y(p.mean)}`).join(" ")} fill="none" className="inv-history-line" />}
      {points.map((point, i) => {
        const current = point.run.baselineSha === currentShas[0] && point.run.candidateSha === currentShas[1], selected = selectedRuns.includes(point.run.id);
        const circle = <circle cx={x(i)} cy={y(point.mean)} r={selected ? 7 : 5} className={`${current ? "inv-history-current" : "inv-history-older"}${selected ? " inv-history-selected" : ""}`}><title>{`${shortSha(point.run.commitSha)} · ${String(point.mean)} ${unit} · ${timestamp(point.run.measuredAt)}`}</title></circle>;
        return <g key={point.run.id}>{repo.dataOrigin === "REAL" ? <a href={`${repo.githubUrl}/commit/${point.run.commitSha}`} target="_blank" rel="noreferrer" aria-label={`Open commit ${shortSha(point.run.commitSha)}`}>{circle}</a> : circle}
          <text x={x(i)} y={bottom + 22} textAnchor="middle">{shortSha(point.run.commitSha)}</text></g>;
      })}
      <text x={left} y="199">Mean of JVM-fork means · {unit} · commits ordered by measurement time</text>
    </svg>
    <figcaption>Each point is a persisted run. Lines stay within matching conditions; they do not imply causation or long-term trends.</figcaption>
  </figure>;
}
