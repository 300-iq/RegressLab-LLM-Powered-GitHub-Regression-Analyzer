import Link from "next/link";
import type { Analysis, BenchmarkCase } from "@/lib/contracts";
import type { AnalysisState, Evidence, InvestigationData } from "@/lib/investigation-data";
import { observedEffect, statusLabel, trustDescription, whatChanged } from "@/lib/investigation";
import { historyGroups, latestComparisons, number, reasonLabel, shortSha, timestamp } from "@/lib/presentation";
import { queryHref } from "@/lib/results";
import { DistributionPair, CommitHistoryPlot } from "./investigation-charts";
import { GithubLink, MetricTag, OriginBadge, Pagination, Panel } from "./ui";
import { RawSamples } from "./raw-samples";
import { ForkTable, RunConditions } from "./evidence";
import { GithubExecution } from "./github-execution";
import { ComparisonTable, RunTable } from "./tables";

function shortCase(benchmark: BenchmarkCase) { return benchmark.identifier.split(".").at(-1)!; }
function ArmStatistics({ arm, evidence, state }: { arm: "baseline" | "candidate"; evidence: Evidence; state: AnalysisState }) {
  const a = state.kind === "ready" ? state.analysis.result[arm] : null;
  const legacy = state.kind === "empty" ? evidence.comparison.summary : null;
  const median = a?.forkMeans.median ?? (legacy ? arm === "baseline" ? legacy.baselineMedian : legacy.candidateMedian : null);
  const sd = a?.forkMeans.standardDeviation ?? (legacy ? arm === "baseline" ? legacy.baselineStdDev : legacy.candidateStdDev : null);
  const cv = a?.forkMeans.coefficientOfVariation ?? (legacy ? arm === "baseline" ? legacy.baselineCv : legacy.candidateCv : null);
  return <dl className="inv-arm-stats">
    <div><dt>Fork median</dt><dd title={median === null ? undefined : String(median)}>{median === null ? "Unavailable" : number(median)} <small>{evidence.benchmark.unit}</small></dd></div>
    <div><dt>Fork SD / CV</dt><dd>{sd === null ? "—" : number(sd)} <small>{evidence.benchmark.unit}</small><span>{cv === null ? "Unavailable" : `${number(cv * 100)}% CV`}</span></dd></div>
  </dl>;
}
export function WhatChanged({ evidence, state }: { evidence: Evidence; state: AnalysisState }) {
  return <section className="inv-findings" aria-labelledby="what-changed-heading">
    <div className="inv-section-heading"><div><p className="eyebrow">MEASUREMENTS → OBSERVATIONS</p><h2 id="what-changed-heading">What changed?</h2></div><span className="inv-rule-label">Deterministic evidence summary</span></div>
    <ul>{whatChanged(evidence, state).map((f, i) => <li key={f.id} className={`inv-finding ${f.tone}`}><span className="inv-finding-number">{String(i + 1).padStart(2, "0")}</span><div><h3>{f.title}</h3><p>{f.body}</p></div></li>)}</ul>
    <p className="inv-finding-footnote">Derived from stored measurements and analysis policy. No generated explanation or code-level cause is inferred.</p>
  </section>;
}
export function AnalysisExplanation({ state, evidence }: { state: AnalysisState; evidence: Evidence }) {
  const a: Analysis | null = state.kind === "ready" ? state.analysis : null;
  if (!a) return <div className="inv-analysis-missing" role={state.kind === "error" ? "alert" : "note"}><strong>{state.kind === "error" ? "Analysis could not be loaded" : state.snapshots.totalElements ? "No v2 snapshot on this analysis page" : "No current bootstrap analysis stored"}</strong><p>{state.kind === "error" ? state.message : state.snapshots.totalElements ? "No supported v2 snapshot is present on this listing page. Select a preserved policy or another page to inspect its frozen output." : "These are real stored measurements with a descriptive comparison. An administrator must calculate and store an analysis before a threshold or interval can be shown."}</p><ul>{evidence.comparison.reasons.map(r => <li key={r}>{reasonLabel(r)}</li>)}</ul></div>;
  return <section className="inv-verdict" aria-label="Classification evidence">
    <div className="inv-verdict-summary"><span className={`inv-status ${a.result.classification.toLowerCase()}`}>{statusLabel(evidence, state)}</span><p>{a.result.explanation}</p></div>
    <div className="inv-policy-grid"><div><span>Practical change band</span><strong>±{number(a.policy.practicalThresholdPercent)}%</strong><p>Both interval bounds must clear a practical boundary for an indication.</p></div><div><span>{a.result.interval ? a.result.interval.exploratory ? "Exploratory interval" : "Conditional interval" : "Interval unavailable"}</span>
      <strong>{a.result.interval ? `${number(a.result.interval.lowerPercent)}% → ${number(a.result.interval.upperPercent)}%` : "Not available"}</strong>
      <p>{a.result.interval ? `${number(a.result.interval.nominalConfidenceLevel * 100)}% nominal level · ${a.policy.familySize} case family. Coverage is not guaranteed.` : "Invalid comparisons have no relative effect or interval."}</p></div></div>
    {a.result.interval && <p className="inv-method-note">Percentile bootstrap of JVM-fork means · {a.policy.resamples.toLocaleString("en-US")} resamples · seed {a.policy.seed}. {a.result.interval.exploratory ? "Exploratory output cannot override failed adequacy checks." : "Conditional on exchangeable, independent forks and arms."}</p>}
  </section>;
}
export function InvestigationWorkspace({ data, aiPanel }: { data: InvestigationData; aiPanel?:React.ReactNode }) {
  const { pr, repo, path, query, evidence, comparison, analysisState: state, comparisons, history, historyError, outdated } = data;
  if (!evidence || !comparison || !state) return null;
  const a = state.kind === "ready" ? state.analysis : null, effect = observedEffect(evidence, state);
  const pairs = latestComparisons(comparisons.items).filter(item => item.benchmarkCaseId === evidence.benchmark.id);
  const samePair = (item: typeof comparison) => item.baselineRunId === comparison.baselineRunId && item.candidateRunId === comparison.candidateRunId;
  const bRun = evidence.baseline.run, cRun = evidence.candidate.run;
  const groups = history ? historyGroups(history.items) : [];
  return <div className={`investigation-workspace${aiPanel?" with-ai":""}`}>
    <header className="inv-header">
      <div className="inv-header-top"><p className="eyebrow">PERFORMANCE INVESTIGATION <span>/ PR #{pr.number} · {pr.state.toLowerCase()}</span></p><GithubLink url={pr.githubUrl} origin={pr.dataOrigin} provenance={pr.provenance}>GitHub pull request</GithubLink></div>
      <h1>{pr.title}</h1>
      <div className="inv-commit-pair"><span className="inv-commit baseline"><span className="inv-swatch" />Baseline <GithubLink url={`${repo.githubUrl}/commit/${bRun.commitSha}`} origin={bRun.dataOrigin} provenance={bRun.provenance}><code title={bRun.commitSha}>{shortSha(bRun.commitSha)}</code></GithubLink></span><span aria-hidden="true">→</span><span className="inv-commit candidate"><span className="inv-swatch" />Candidate <GithubLink url={`${repo.githubUrl}/commit/${cRun.commitSha}`} origin={cRun.dataOrigin} provenance={cRun.provenance}><code title={cRun.commitSha}>{shortSha(cRun.commitSha)}</code></GithubLink></span><span className="inv-snapshot-label">{outdated ? "Earlier PR snapshot" : "Current PR snapshot"}</span></div>
      {outdated && <div className="inv-historical-notice" role="note"><strong>Earlier commit snapshot</strong><span>This pair differs from the PR’s latest imported commits. You are investigating retained measurements, not the current PR result.</span></div>}
      {a?.analysisVersion === "fork-bootstrap-v1" && <div className="inv-historical-notice" role="note"><strong>Preserved v1 analysis</strong><span>Historical method output; this snapshot has not been recalculated with v2.</span></div>}
      <div className="inv-summary-grid">
        <article className="inv-summary-card"><p className="eyebrow">01 / DID PERFORMANCE CHANGE?</p><strong className={`inv-effect ${effect !== null && effect > 0 ? "observed-worse" : ""}`}>{effect === null ? state.kind === "error" ? "Unavailable" : "Not comparable" : `${number(Math.abs(effect))}%`}<span>{effect === null ? "No valid relative effect available" : effect > 0 ? "observed degradation" : effect < 0 ? "observed improvement" : "observed change"}</span></strong><p>{a ? "Median of JVM-fork means" : "Mean of JVM-fork means · descriptive"} · {evidence.benchmark.unit}</p></article>
        <article className="inv-summary-card inv-trust"><p className="eyebrow">02 / HOW TRUSTWORTHY IS IT?</p><strong className="inv-classification">{statusLabel(evidence, state)}</strong><p>{trustDescription(state)}</p><OriginBadge origin={pr.dataOrigin} provenance={a?.provenance ?? comparison.provenance} /><p className="inv-origin-note">{(a?.provenance ?? comparison.provenance) === "GITHUB_METADATA" ? "Artifact origin checked; measurement claims remain untrusted." : "Local provenance is unverified."}</p></article>
        <article className="inv-summary-card"><p className="eyebrow">03 / WHAT SUPPORTS THE OBSERVATION?</p><strong className="inv-count">{evidence.baselineBenchmark.configuration.forkCount} / {evidence.candidateBenchmark.configuration.forkCount}<span>JVM forks</span></strong><p>{evidence.baselineSamples.values.length} baseline / {evidence.candidateSamples.values.length} candidate raw iterations. Shared runner noise remains.</p><a className="text-link" href="#raw-samples">Inspect every observation ↓</a></article>
      </div>
    </header>
    <div className="inv-workbench">
      <aside className="inv-explorer" aria-label="Benchmark explorer">
        <div className="inv-explorer-heading"><p className="eyebrow">BENCHMARK EXPLORER</p><span>{data.catalog.length} cases</span></div>
        <div className="inv-case-options">{data.catalog.map(benchmark => {
          const target = latestComparisons(comparisons.items).find(item => samePair(item) && item.benchmarkCaseId === benchmark.id);
          return target ? <Link key={benchmark.id} href={queryHref(path, query, { comparison: target.id, analysis: undefined, snapshotsPage: undefined, historyPage: undefined, aiPage:undefined })}
            aria-current={benchmark.id === evidence.benchmark.id ? "true" : undefined} className="inv-case" aria-label={`Inspect ${shortCase(benchmark)}`}><span>{shortCase(benchmark)}</span><small>{benchmark.unit} · {benchmark.direction === "LOWER" ? "Lower" : "Higher"} is better</small><code>{benchmark.identifier}</code></Link> : <div key={benchmark.id} className="inv-case unavailable"><span>{shortCase(benchmark)}</span><small>No comparison on this listing page</small></div>;
        })}</div>
        <nav className="inv-section-nav" aria-label="Investigation sections">{["Measurements", "Conditions", "History", "Notes"].map((section, i) => <a key={section} href={`#inv-${section.toLowerCase()}`}><span>{String(i + 1).padStart(2, "0")}</span>{section}<span aria-hidden="true">↗</span></a>)}</nav>
        <div className="inv-explorer-note"><strong>Follow the evidence.</strong><p>A large percentage is an observation. Adequate, comparable measurements determine the classification.</p></div>
      </aside>
      <div className="inv-content">
        <div className="ai-workspace-grid"><div className="ai-measurements">
        <section id="inv-measurements" className="inv-measurement-section" aria-labelledby="measurements-heading">
          <div className="inv-section-heading"><div><p className="eyebrow">SELECTED BENCHMARK</p><h2 id="measurements-heading">{shortCase(evidence.benchmark)}</h2><code className="inv-full-case">{evidence.benchmark.identifier}</code></div><MetricTag benchmark={evidence.benchmark} /></div>
          <div className="inv-controls">
            <form key={comparison.id} className="filter-form inv-pair-filter"><div className="field"><label htmlFor="investigation-pair">Compared runs</label><select id="investigation-pair" name="comparison" defaultValue={comparison.id}>
              {!pairs.some(item => item.id === comparison.id) && <option value={comparison.id}>{comparison.analysisVersion} · selected preserved comparison</option>}
              {pairs.map(item => { const b = data.detail.runs.items.find(run => run.id === item.baselineRunId), c = data.detail.runs.items.find(run => run.id === item.candidateRunId);
                return <option key={item.id} value={item.id}>{b && c ? `${shortSha(b.commitSha)} → ${shortSha(c.commitSha)}` : `Pair ${item.id.slice(0, 8)}`} · {timestamp(item.createdAt)}</option>; })}</select></div><button className="button button-secondary" type="submit">Inspect runs</button></form>
            {state.kind !== "error" && state.snapshots.items.length > 0 && <form key={`${comparison.id}:${a?.id ?? "none"}`} className="filter-form inv-policy-filter"><input type="hidden" name="comparison" value={comparison.id} /><div className="field"><label htmlFor="investigation-analysis">Stored analysis policy</label><select id="investigation-analysis" name="analysis" defaultValue={a?.id ?? ""}><option value="">Latest v2 snapshot on this page</option>
              {a && !state.snapshots.items.some(item => item.id === a.id) && <option value={a.id}>{a.analysisVersion} · ±{a.policy.practicalThresholdPercent}%</option>}
              {state.snapshots.items.map(item => <option key={item.id} value={item.id}>{item.analysisVersion} · ±{item.policy.practicalThresholdPercent}% · {timestamp(item.createdAt)}</option>)}</select></div><button className="button button-secondary" type="submit">Inspect policy</button></form>}
          </div>
          <DistributionPair key={comparison.id} baseline={evidence.baselineSamples.values} candidate={evidence.candidateSamples.values} unit={evidence.benchmark.unit}
            baselineMedian={a?.result.baseline?.rawMeasurements.median} candidateMedian={a?.result.candidate?.rawMeasurements.median}
            baselineStats={<ArmStatistics arm="baseline" evidence={evidence} state={state} />} candidateStats={<ArmStatistics arm="candidate" evidence={evidence} state={state} />} />
          <AnalysisExplanation state={state} evidence={evidence} />
          {state.kind !== "error" && <Pagination data={state.snapshots} path={path} query={{ ...query, comparison: comparison.id, analysis: a?.id ?? query.analysis }} parameter="snapshotsPage" label="analysis snapshots" />}
        </section>
        <WhatChanged evidence={evidence} state={state} />
        </div>{aiPanel}</div>
        <section className="inv-raw-section" aria-labelledby="raw-heading"><div className="inv-section-heading"><div><p className="eyebrow">THE SOURCE OF TRUTH</p><h2 id="raw-heading">Raw observations</h2></div><span className="inv-rule-label">Full precision · all pages loaded</span></div><p className="inv-intro">Select an arm and inspect preserved fork and iteration indices. Displayed samples are not independent inferential units.</p><RawSamples key={comparison.id} baseline={evidence.baselineSamples.values} candidate={evidence.candidateSamples.values} unit={evidence.benchmark.unit} />
          <details className="inv-details"><summary>Repeat measurement variability by fork</summary><ForkTable baseline={evidence.baselineSamples.values} candidate={evidence.candidateSamples.values} unit={evidence.benchmark.unit} /></details></section>
        <section id="inv-conditions" className="inv-section" aria-labelledby="conditions-heading"><div className="inv-section-heading"><div><p className="eyebrow">COMPARABILITY BEFORE CONCLUSIONS</p><h2 id="conditions-heading">Test conditions</h2></div><span className={`inv-condition-badge ${comparison.analysisStatus === "INCOMPATIBLE" ? "mismatch" : ""}`}>{bRun.environmentSha256 === cRun.environmentSha256 && evidence.baselineBenchmark.configurationSha256 === evidence.candidateBenchmark.configurationSha256 ? "Recorded conditions match" : "Conditions differ"}</span></div>
          <p className="inv-intro">Matching metadata does not establish identical CPU contention, scheduling, thermal state or warmup convergence.</p>
          {a?.result.environmentWarnings.map(warning => <p key={warning} className="inv-condition-warning">{warning}</p>)}
          <div className="inv-condition-grid"><RunConditions detail={evidence.baseline} benchmark={evidence.baselineBenchmark} label="Baseline" /><RunConditions detail={evidence.candidate} benchmark={evidence.candidateBenchmark} label="Candidate" /></div>
          <details className="inv-details"><summary>GitHub execution evidence and alternating order</summary><GithubExecution run={bRun} /></details>
        </section>
        <section id="inv-history" className="inv-section" aria-labelledby="history-heading"><div className="inv-section-heading"><div><p className="eyebrow">BEYOND THIS COMPARISON</p><h2 id="history-heading">History across commits</h2></div><span className="inv-rule-label">{history ? `${groups.length} condition ${groups.length === 1 ? "group" : "groups"}` : "History unavailable"}</span></div>
          <p className="inv-intro">History includes baseline and candidate runs. Current PR measurements and earlier snapshots are labeled separately. Groups with different environments or configurations are never connected.</p>
          <div className="inv-history-legend"><span><i className="inv-history-key current" />Current PR snapshot</span><span><i className="inv-history-key older" />Historical snapshot</span><span>Outlined point = selected run pair</span></div>
          {historyError ? <div role="alert" className="inv-analysis-missing">{historyError}</div> : groups.length ? groups.map(group => <div key={group.key} className="inv-history-group"><div className="inv-history-conditions"><span>Environment <code>{group.points[0].run.environmentSha256.slice(0, 10)}</code></span><span>Configuration <code>{group.points[0].configurationSha256.slice(0, 10)}</code></span></div>
            <CommitHistoryPlot points={group.points} repo={repo} selectedRuns={[bRun.id, cRun.id]} currentShas={[pr.baselineSha, pr.candidateSha]} unit={evidence.benchmark.unit} />
            <div className="table-scroll" tabIndex={0} aria-label="Commit history"><table><caption className="sr-only">Commit measurements and PR snapshot context</caption><thead><tr><th scope="col">Commit / context</th><th scope="col">Mean ({evidence.benchmark.unit})</th><th scope="col">Measurements</th><th scope="col">Investigate</th></tr></thead><tbody>{group.points.map(point => {
              const current = point.run.baselineSha === pr.baselineSha && point.run.candidateSha === pr.candidateSha;
              return <tr key={point.run.id}><td><GithubLink url={`${repo.githubUrl}/commit/${point.run.commitSha}`} origin={point.run.dataOrigin} provenance={point.run.provenance}><code>{shortSha(point.run.commitSha)}</code></GithubLink><small className="inv-history-context">{current ? "Current PR snapshot" : "Historical snapshot"} · {timestamp(point.run.measuredAt)}</small></td><td><code>{String(point.mean)}</code></td><td>{point.forks} forks · {point.sampleCount} iterations</td><td><Link className="text-link" href={`/investigations/${point.run.pullRequestId}?run=${point.run.id}&case=${evidence.benchmark.id}`}>Inspect run ↗</Link></td></tr>;
            })}</tbody></table></div></div>) : <p className="inline-empty">No historical measurements are available on this page.</p>}
          {history && <Pagination data={history} path={path} query={{ ...query, comparison: comparison.id }} parameter="historyPage" label="history observations" />}
        </section>
        <section id="inv-notes" className="inv-section inv-notes" aria-labelledby="notes-heading"><div className="inv-section-heading"><div><p className="eyebrow">SUPPORT, UNCERTAINTY, LIMITS</p><h2 id="notes-heading">Investigation notes</h2></div></div>
          <div className="inv-note"><span>01</span><div><h3>What the measurements support</h3><p>{a?.result.explanation ?? "The stored comparison describes an observed effect under recorded conditions. No inferential classification has been substituted."}</p></div></div>
          <div className="inv-note"><span>02</span><div><h3>What they do not establish</h3><p>Timing alone does not isolate a code-level cause. No statement here attributes the change to a particular function, commit edit or resource bottleneck.</p></div></div>
          <details className="inv-details" open><summary>Measurement uncertainty and environmental confounders</summary><ul>{(a?.result.limitations ?? ["Repeated iterations within one JVM fork are dependent.", "Shared runner contention, drift and scheduling remain possible confounders.", "Descriptive measurements alone cannot establish statistical significance or causation."]).map(text => <li key={text}>{text}</li>)}</ul></details>
          <details className="inv-details"><summary>Reproduce this stored evidence snapshot</summary><dl className="condition-list"><div><dt>Comparison ID</dt><dd>{comparison.id}</dd></div><div><dt>Analysis method</dt><dd>{a?.analysisVersion ?? comparison.analysisVersion}</dd></div>{a && <><div><dt>Analysis ID</dt><dd>{a.id}</dd></div><div><dt>Policy SHA-256</dt><dd>{a.policySha256}</dd></div><div><dt>Input SHA-256</dt><dd>{a.inputSha256}</dd></div><div><dt>Calculated at</dt><dd>{timestamp(a.createdAt)}</dd></div></>}</dl></details>
        </section>
        <details className="inv-details inv-archive"><summary>Comparison archive · {comparisons.totalElements} retained records</summary><ComparisonTable comparisons={comparisons.items} cases={data.catalog} prId={pr.id} selected={comparison.id} /><Pagination data={comparisons} path={path} query={query} parameter="analysesPage" label="comparisons" /><Panel title="PR benchmark runs"><RunTable runs={data.detail.runs.items} /><Pagination data={data.detail.runs} path={path} query={query} parameter="runsPage" label="runs" /></Panel></details>
      </div>
    </div>
  </div>;
}
