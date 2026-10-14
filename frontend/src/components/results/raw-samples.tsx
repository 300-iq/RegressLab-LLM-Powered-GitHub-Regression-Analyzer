"use client";
import { useId, useState } from "react";
import type { Sample } from "@/lib/contracts";
export function RawSamples({
  baseline,
  candidate = [],
  unit,
  baselineLabel = "Baseline",
  candidateLabel = "Candidate",
}: {
  baseline: Sample[];
  candidate?: Sample[];
  unit: string;
  baselineLabel?: string;
  candidateLabel?: string;
}) {
  const [arm, setArm] = useState<"baseline" | "candidate">("baseline");
  const forkId = useId();
  const [fork, setFork] = useState("all");
  const [page, setPage] = useState(0);
  const allSamples = arm === "baseline" ? baseline : candidate;
  const forks = [...new Set(allSamples.map(s => s.forkIndex))].sort((a,b) => a-b);
  const samples = fork === "all" ? allSamples : allSamples.filter(s => s.forkIndex === Number(fork));
  const size = 20,
    pages = Math.max(1, Math.ceil(samples.length / size));
  return (
    <div id="raw-samples">
      <div className="sample-toolbar">
        <div role="group" aria-label="Raw sample arm">
          {(["baseline", "candidate"] as const)
            .filter((side) => side === "baseline" || candidate.length > 0)
            .map((side) => (
              <button
                key={side}
                aria-pressed={arm === side}
                className={arm === side ? "segmented active" : "segmented"}
                onClick={() => {
                  setArm(side);
                  setFork("all");
                  setPage(0);
                }}
              >
                {side === "baseline" ? baselineLabel : candidateLabel}
              </button>
            ))}
        </div>
        <span className="inv-fork-filter"><label htmlFor={forkId}>JVM fork</label><select id={forkId} value={fork} onChange={event => {setFork(event.target.value); setPage(0);}}><option value="all">All forks</option>{forks.map(f => <option value={f} key={f}>Fork {f}</option>)}</select></span>
        <span>
          {samples.length} stored observations · {unit}
        </span>
      </div>
      {samples.length ? (
        <div
          className="table-scroll"
          tabIndex={0}
          aria-label="Raw measurement samples"
        >
          <table>
            <caption className="sr-only">
              {arm === "baseline" ? baselineLabel : candidateLabel} raw
              iterations in {unit}
            </caption>
            <thead>
              <tr>
                <th scope="col">Fork index</th>
                <th scope="col">Iteration index</th>
                <th scope="col">Value ({unit})</th>
              </tr>
            </thead>
            <tbody>
              {samples.slice(page * size, (page + 1) * size).map((sample) => (
                <tr key={`${sample.forkIndex}:${sample.iterationIndex}`}>
                  <td>{sample.forkIndex}</td>
                  <td>{sample.iterationIndex}</td>
                  <td>
                    <code>{String(sample.value)}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="inline-empty">No observations stored for this run.</p>
      )}
      <nav className="pagination" aria-label="Raw sample pagination">
        <span>
          Page {page + 1} of {pages} · full values remain in the API
        </span>
        <div>
          <button
            className="button button-secondary"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </button>
          <button
            className="button button-secondary"
            disabled={page + 1 >= pages}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </div>
      </nav>
    </div>
  );
}
