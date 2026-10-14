import type { GithubAudit, Run } from "@/lib/contracts";
import { attempt, getGithubAudit } from "@/lib/results";
import { GithubLink, Panel } from "./ui";
export function ExecutionEvidence({audit,run}: {audit:GithubAudit;run:Run}) {
  const execution=audit.execution;
  return <Panel title="GitHub execution evidence" description="Reported execution order and failures, preserved from the uploaded artifact. GitHub origin checks do not attest to measurements.">
    <div className="panel-controls"><GithubLink url={run.githubUrl} origin={run.dataOrigin} provenance={run.provenance}>Open workflow run</GithubLink><span className="badge">Alternating JVM forks</span></div>
    <div className="table-scroll"><table aria-label="Reported GitHub benchmark execution order"><thead><tr><th scope="col">Order</th><th scope="col">Arm / fork</th><th scope="col">Reported status</th><th scope="col">Duration</th><th scope="col">Exit / failure</th></tr></thead><tbody>{execution.blocks.map((block,index)=><tr key={index}><td>{index+1}</td><td>{block.arm.toLowerCase()} / {block.forkIndex}</td><td>{block.status}</td><td>{(block.elapsedMs/1000).toFixed(1)} s</td><td>{block.exitCode??"—"}{block.error && <span> · {block.error}</span>}</td></tr>)}</tbody></table></div>
    <div className="panel-controls"><p>Build status: baseline <strong>{execution.builds.BASELINE.status}</strong> · candidate <strong>{execution.builds.CANDIDATE.status}</strong></p></div>
    {(["BASELINE", "CANDIDATE"] as const).map(arm => {
      const build=execution.builds[arm];
      return build.status!=="COMPLETE" && <p className="panel-controls" key={arm}><strong>{arm.toLowerCase()} build / correctness:</strong>{" "}{build.status} · exit code {build.exitCode??"not recorded"}{build.error && <span> · {build.error}</span>}</p>;
    })}
    <details className="content-panel"><summary>Original artifact audit</summary><p>Artifact {audit.githubArtifactId} · metadata checked {audit.verifiedAt}</p><p>ZIP SHA-256 <code className="hash-value">{audit.archiveSha256}</code></p><p>Harness SHA-256 <code className="hash-value">{execution.harnessSha256}</code></p><p>Untrusted PR code shares this runner. The importer validates structure and provenance; it cannot prove measurements are honest or eliminate hosted-runner noise.</p></details>
  </Panel>;
}
export async function GithubExecution({run}: {run:Run}) {
  if(run.provenance!=="GITHUB_METADATA") return null;
  const result=await attempt(()=>getGithubAudit(run.id));
  if(result.kind==="error") return <Panel title="GitHub execution evidence"><p className="inline-empty">Execution evidence could not be loaded. Measurement results do not imply a successful audit.</p></Panel>;
  return <ExecutionEvidence audit={result.value} run={run}/>;
}
