import Link from "next/link";
import type { comparisonEvidence } from "@/lib/workspace";
import { shortSha } from "@/lib/presentation";
import { DistributionChart } from "./charts";
import {
  ComparisonStatistics,
  EvidenceSummary,
  ForkTable,
  RunConditions,
} from "./evidence";
import { RawSamples } from "./raw-samples";
import { Panel } from "./ui";
import { GithubExecution } from "./github-execution";
export function ComparisonView({
  evidence,
  controls,
}: {
  evidence: Awaited<ReturnType<typeof comparisonEvidence>>;
  controls?: React.ReactNode;
}) {
  const {
    comparison,
    benchmark,
    baseline,
    candidate,
    baselineBenchmark,
    candidateBenchmark,
    baselineSamples,
    candidateSamples,
  } = evidence;
  return (
    <>
      <EvidenceSummary comparison={comparison} benchmark={benchmark} />
      {controls}
      <div className="commit-strip">
        <span>
          <span className="arm-dot baseline" />
          Baseline{" "}
          <code title={baseline.run.commitSha}>
            {shortSha(baseline.run.commitSha)}
          </code>
        </span>
        <span aria-hidden="true">→</span>
        <span>
          <span className="arm-dot candidate" />
          Candidate{" "}
          <code title={candidate.run.commitSha}>
            {shortSha(candidate.run.commitSha)}
          </code>
        </span>
        <Link
          className="text-link"
          href={`/benchmarks/${benchmark.id}?run=${candidate.run.id}&comparison=${comparison.id}`}
        >
          Benchmark history ↗
        </Link>
      </div>
      <div className="two-column">
        <Panel
          title="Measurement distributions"
          description="All stored iterations; shared value bins for both arms."
        >
          <DistributionChart
            baseline={baselineSamples.values}
            candidate={candidateSamples.values}
            unit={benchmark.unit}
          />
        </Panel>
        <Panel
          title="Descriptive statistics"
          description="Backend calculations across fork means."
        >
          <ComparisonStatistics comparison={comparison} unit={benchmark.unit} />
          {!comparison.summary && (
            <p className="inline-empty">
              No computed summary: the recorded conditions are incompatible.
            </p>
          )}
        </Panel>
      </div>
      <Panel
        title="Repeat measurement variability"
        description="Each row summarizes one fresh JVM fork; shared runner noise can correlate forks."
      >
        <ForkTable
          baseline={baselineSamples.values}
          candidate={candidateSamples.values}
          unit={benchmark.unit}
        />
      </Panel>
      <Panel
        title="Raw samples"
        description="Preserved fork and iteration indices. No observations are excluded from the distribution."
      >
        <RawSamples
          key={comparison.id}
          baseline={baselineSamples.values}
          candidate={candidateSamples.values}
          unit={benchmark.unit}
        />
      </Panel>
      <GithubExecution run={baseline.run} />
      <div className="two-column">
        <RunConditions
          detail={baseline}
          benchmark={baselineBenchmark}
          label="Baseline"
        />
        <RunConditions
          detail={candidate}
          benchmark={candidateBenchmark}
          label="Candidate"
        />
      </div>
    </>
  );
}
