"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { Analysis, Page } from "@/lib/contracts";
import { aiFailure, aiReportSchema, aiJobSchema, matchesJob, codeReference, matchesAiContext, type AiContext, type AiFailure, type AiReport, type AiJob } from "@/lib/ai-contracts";
import { number, shortSha, timestamp } from "@/lib/presentation";

export type AiLoad={kind:"ready";page:Page<AiReport>}|{kind:"error"};
export function AiReportView({report,currentAnalysisId}:{report:AiReport;currentAnalysisId?:string}) {
  const a=report.input.evidence.analysis, r=a.result, diff=report.input.diff;
  return <div className="ai-report">
    <div className="ai-report-meta"><span className="badge">Unverified hypotheses</span><span>{report.model} · {report.reportVersion}</span><time dateTime={report.createdAt}>{timestamp(report.createdAt)}</time></div>
    {currentAnalysisId!==a.id && <p className="ai-snapshot-note" role="note">Stored report snapshot: its analysis is separate from the selected measurement view. The summary below uses the report’s frozen evidence.</p>}
    {r.relativeChangePercent!==null && Math.abs(r.relativeChangePercent)<a.policy.practicalThresholdPercent && <p className="ai-snapshot-note" role="note">Retained below-threshold report. Its observed effect does not meet the current generation guard. Historical hypotheses may be irrelevant; they are preserved for audit.</p>}
    <section className="ai-stored-evidence" aria-label="Supporting benchmark evidence"><p className="eyebrow">SERVER MEASUREMENTS · NOT MODEL CLAIMS</p><h3>Performance change summary</h3>
      <strong title={String(r.relativeChangePercent)}>{r.relativeChangePercent===null?"Effect unavailable":`${number(Math.abs(r.relativeChangePercent))}% observed ${r.relativeChangePercent>0?"degradation":r.relativeChangePercent<0?"improvement":"change"}`}</strong>
      <p>{r.classification.replaceAll("_"," ").toLowerCase()} · {r.unit} · {r.direction==="LOWER"?"Lower":"Higher"} is better</p><code className="inv-full-case">{r.benchmarkIdentifier}</code><p>{r.explanation}</p>
      <dl className="ai-evidence-grid">{(["baseline","candidate"] as const).map(arm=><div key={arm}><dt>{arm} median of fork means</dt><dd>{String(r[arm]?.forkMeans.median)} <small>{r.unit}</small></dd><span>{r[arm]?.forkMeans.count} forks · {r[arm]?.rawMeasurements.count} iterations</span></div>)}</dl>
      <p>Practical threshold: ±{a.policy.practicalThresholdPercent}%. Raw iterations are dependent. No root cause is established.</p>
      <div className="ai-commit-links"><a href={`https://github.com/${diff.repository}/commit/${diff.baselineSha}`} target="_blank" rel="noreferrer">Baseline {shortSha(diff.baselineSha)} ↗</a><a href={`https://github.com/${diff.repository}/commit/${diff.candidateSha}`} target="_blank" rel="noreferrer">Candidate {shortSha(diff.candidateSha)} ↗</a></div>
    </section>
    <div className="ai-quality-warning" role="note"><strong>Review before acting</strong><p>Model prose may be wrong, irrelevant, or propose inadequate experiments. References prove location existence, not causation.</p>
      <ul>{report.limitations.filter(l=>l.startsWith("Hypothesis ")).map((l,i)=><li key={i}>{l}</li>)}</ul></div>
    {report.report.hypotheses.length===0?<div className="ai-empty"><h3>No hypotheses returned</h3><p>The stored report did not propose a useful mechanism. Inspect its missing evidence below.</p></div>:
      report.report.hypotheses.map((h,i)=><article className="ai-hypothesis" key={i}><p className="eyebrow">HYPOTHESIS {String(i+1).padStart(2,"0")} · NOT CONFIRMED</p><h3>{h.potentialCause}</h3><p>{h.technicalExplanation}</p>
        <div className="ai-code-references" aria-label={`Changed code for hypothesis ${i+1}`}>{h.references.map((ref,j)=>{
          const code=codeReference(report,ref);if(!code)return null;
          return <details key={j}><summary><span>{ref.side.toLowerCase()} · {code.filePath}:{ref.line}</span></summary><a className="text-link" href={code.url} target="_blank" rel="noreferrer">Open changed line {ref.line} on GitHub ↗</a><pre><code>{code.code}</code></pre></details>;
        })}</div>
        <div className="ai-experiment"><h4>Suggested verification experiment</h4><span>Model suggestion · not executed</span><p>{h.verificationExperiment}</p><p className="ai-experiment-rule">Use warmup, repeated independent JVM forks and matched conditions. Timing a single invocation is inadequate regression evidence.</p></div>
        <div className="ai-uncertainty"><h4>Uncertainty / missing evidence</h4><p>{h.uncertainty}</p></div>
      </article>)}
    <section className="ai-missing"><h3>Evidence still missing</h3><ul>{report.report.missingEvidence.map((text,i)=><li key={i}>{text}</li>)}</ul></section>
    <details className="ai-audit"><summary>Report provenance and limitations</summary><ul>{report.limitations.map((text,i)=><li key={i}>{text}</li>)}</ul><dl><dt>Report ID</dt><dd>{report.id}</dd><dt>Analysis ID</dt><dd>{a.id}</dd><dt>Input SHA-256</dt><dd>{report.inputSha256}</dd><dt>Model digest</dt><dd>{report.modelDigest}</dd></dl></details>
  </div>;
}
export function AiInvestigation({initial,initialJob,context,analysis,localEnabled,outdated,pageHref}:{initial:AiLoad;initialJob?:AiJob|null;context:AiContext;analysis:Analysis|null;localEnabled:boolean;outdated:boolean;pageHref:{previous?:string;next?:string}}) {
  const [reports,setReports]=useState(initial.kind==="ready"?initial.page.items:[]);
  const [selected,setSelected]=useState(reports[0]?.id??"");
  const [job,setJob]=useState<AiJob|null>(initialJob??null);
  const [state,setState]=useState<{kind:"idle"|"generating"|"complete";reused?:boolean}|AiFailure>(initialJob?.status==="FAILED"?aiFailure(initialJob.errorCode??"AI_FAILED"):
    initialJob && ["QUEUED","RUNNING"].includes(initialJob.status)?{kind:"generating"}:{kind:"idle"});
  const controller=useRef<AbortController|null>(null), active=useRef(!!initialJob&&["QUEUED","RUNNING"].includes(initialJob.status));
  useEffect(()=>()=>controller.current?.abort(),[]);
  const report=reports.find(r=>r.id===selected);
  const eligible=analysis && analysis.analysisVersion==="fork-bootstrap-v2" && analysis.dataOrigin==="REAL" && analysis.provenance==="GITHUB_METADATA" &&
    analysis.result.classification!=="INVALID_COMPARISON" && analysis.result.relativeChangePercent!==null && Math.abs(analysis.result.relativeChangePercent)>=analysis.policy.practicalThresholdPercent && !outdated;
  const generating=state.kind==="generating";
  const jobId=job?.id, analysisId=analysis?.id;
  useEffect(()=>{
    if(!jobId || !analysisId)return;
    const abort=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined;
    async function poll() {
      try {
        const query=new URLSearchParams({jobId:jobId!,comparisonId:context.comparisonId,analysisId:analysisId!});
        const response=await fetch(`/api/ai-investigations?${query}`,{cache:"no-store",signal:abort.signal});
        const data=await response.json();if(!response.ok)throw Error("Status unavailable");
        const nextJob=aiJobSchema.parse(data.job);
        if(!matchesJob(nextJob,context.comparisonId,analysisId!))throw Error("Job scope mismatch");
        setJob(nextJob);
        if(data.kind==="pending" && ["QUEUED","RUNNING"].includes(nextJob.status)) {
          active.current=true;setState({kind:"generating"});timer=setTimeout(poll,2000);return;
        }
        active.current=false;
        if(data.kind==="failed" && nextJob.status==="FAILED"){setState(aiFailure(nextJob.errorCode??"AI_FAILED"));return;}
        const report=aiReportSchema.parse(data.report);
        if(nextJob.status!=="SUCCEEDED" || report.id!==nextJob.reportId || !matchesAiContext(report,context) || report.input.evidence.analysis.id!==analysisId)throw Error("Report scope mismatch");
        setReports(current=>[report,...current.filter(r=>r.id!==report.id)]);setSelected(report.id);setState({kind:"complete",reused:nextJob.reused});
      }catch { if(!abort.signal.aborted){active.current=false;setState(aiFailure("CONNECTION_ERROR"));} }
    }
    void poll();return()=>{abort.abort();if(timer)clearTimeout(timer);};
  },[jobId,analysisId,context]);
  async function generate() {
    if(!analysis || !eligible || !localEnabled || active.current)return;
    active.current=true;controller.current=new AbortController();setState({kind:"generating"});
    try {
      const response=await fetch("/api/ai-investigations",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({comparisonId:context.comparisonId,analysisId:analysis.id}),signal:controller.current.signal});
      const data=await response.json();
      if(!response.ok){active.current=false;setState(aiFailure(typeof data.code==="string"?data.code:"API_ERROR"));return;}
      const accepted=aiJobSchema.parse(data.job);
      if(response.status!==202 || !matchesJob(accepted,context.comparisonId,analysis.id))throw Error("Invalid job context");
      setJob(accepted);
    }catch {if(!controller.current?.signal.aborted){active.current=false;setState(aiFailure("CONNECTION_ERROR"));}}
  }
  const status=generating?(job?.status==="QUEUED"?"Queued investigation":"Generating report"):state.kind==="failure"?state.status==="ollama-unavailable"?"Ollama unavailable":state.status==="insufficient-evidence"?"Insufficient evidence":"Failed analysis":report?"Investigation complete":initial.kind==="error"?"Stored reports unavailable":!eligible?"Insufficient evidence":"Ready to investigate";
  return <aside id="ai-investigation" className="ai-investigation" aria-labelledby="ai-investigation-heading" aria-busy={generating}>
    <header className="ai-panel-header"><p className="eyebrow">LOCAL AI / EVIDENCE-LED INVESTIGATION</p><h2 id="ai-investigation-heading">Investigate beyond the number.</h2><p>Explore possible mechanisms against the actual changed code. Hypotheses never replace the measurement verdict.</p>
      <button className="button button-primary ai-investigate-button" onClick={generate} disabled={generating||!eligible||!localEnabled} aria-describedby="ai-generation-policy">{generating?"Generating report…":"Investigate with AI"}<span aria-hidden="true">↗</span></button>
      <p id="ai-generation-policy" className="ai-policy">{!eligible?"Generation needs an authorized current comparison, a stored v2 analysis, and an observed effect reaching its practical threshold. Inconclusive measurements can support exploratory hypotheses, never confirmed causes.":!localEnabled?"Read-only mode. Start the protected local AI preview to generate; administrator credentials stay server-side.":"Runs on the local Ollama server through Spring Boot. May take several minutes. One inference job at a time. Unchanged evidence/model reports are reused; no automatic inference retries."}</p>
    </header>
    <div className={`ai-request-status ${state.kind==="failure"?"error":""}`} role={state.kind==="failure"?"alert":"status"} aria-live="polite"><span className={generating?"ai-status-dot generating":"ai-status-dot"} aria-hidden="true"/><strong>{status}</strong>
      {state.kind==="failure"&&<p>{state.message}</p>}{state.kind==="complete"&&state.reused&&<p>Reused a validated report for unchanged evidence and model configuration. No new inference was run.</p>}{job&&<small>Job {job.id} · {job.status.toLowerCase()}</small>}{generating&&<p>Checking the PR snapshot, generating structured hypotheses and validating every citation. You can continue inspecting measurements.</p>}</div>
    {initial.kind==="error"&&<div className="ai-quality-warning" role="alert">Stored reports could not be loaded or failed validation. Benchmark measurements remain available. Reload to retry.</div>}
    {reports.length>0&&<div className="ai-report-picker"><label htmlFor="ai-stored-report">Stored investigation reports</label><select id="ai-stored-report" value={selected} onChange={e=>{setSelected(e.target.value);if(!generating)setState({kind:"idle"});}}>{reports.map(r=><option key={r.id} value={r.id}>{timestamp(r.createdAt)} · {r.reportVersion} · {r.model}</option>)}</select><span>Immutable attempts stored in PostgreSQL</span></div>}
    {report?<AiReportView report={report} currentAnalysisId={analysis?.id}/>:!generating&&initial.kind==="ready"&&<div className="ai-empty"><span aria-hidden="true">◇</span><h3>No stored investigation on this page</h3><p>Benchmark evidence is already available on the left. A validated report will add possible code mechanisms, exact references, experiments and uncertainty here.</p></div>}
    {(pageHref.previous||pageHref.next)&&<nav className="ai-pages" aria-label="AI report pagination">{pageHref.previous&&<Link className="text-link" href={pageHref.previous}>← Previous reports</Link>}{pageHref.next&&<Link className="text-link" href={pageHref.next}>Older reports →</Link>}</nav>}
  </aside>;
}
