import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { aiFailure, aiReportSchema, aiJobSchema, codeReference, matchesAiContext } from "./ai-contracts";
import { authorizedLocalUiRequest, boundedText, localAiConfiguration } from "./local-ai";
import { AiInvestigation, AiReportView } from "@/components/results/ai-investigation";
import { GET, POST } from "@/app/api/ai-investigations/route";

// Genuine captured output is test input only. Production always reads the API.
const original=JSON.parse(readFileSync(new URL("../test-fixtures/github-pr3-ai-report.json",import.meta.url),"utf8"));
const report=aiReportSchema.parse(original), a=report.input.evidence.analysis;
const context={comparisonId:report.comparisonId,repository:report.input.diff.repository,prNumber:report.input.diff.pullRequestNumber,
  baselineSha:report.input.diff.baselineSha,candidateSha:report.input.diff.candidateSha,benchmarkIdentifier:a.result.benchmarkIdentifier!,unit:a.result.unit!,direction:a.result.direction!};
afterEach(()=>{vi.unstubAllEnvs();vi.unstubAllGlobals();});
describe("AI evidence contract",()=>{
  it("accepts genuine persisted reports including null legacy conditions",()=>{
    expect(matchesAiContext(report,context)).toBe(true);
    const old=structuredClone(original);old.input.evidence.conditions=null;old.reportVersion="ollama-hypotheses-v1";
    expect(aiReportSchema.safeParse(old).success).toBe(true);
  });
  it("validates v5 provider configuration while preserving historical reports",()=>{
    const next=structuredClone(original);next.reportVersion="ollama-hypotheses-v5";
    next.input.evidence.conditions.inferenceConfiguration={providerUrl:"http://127.0.0.1:80",model:next.model,options:{temperature:0,seed:20261014,num_ctx:16384,num_predict:4096},think:false,stream:false,keep_alive:0,timeoutSeconds:120,promptBudgetBytes:12000};
    expect(aiReportSchema.safeParse(next).success).toBe(true);
    next.input.evidence.conditions.inferenceConfiguration.providerUrl="https://remote.example:11434";
    expect(aiReportSchema.safeParse(next).success).toBe(false);
    delete next.input.evidence.conditions.inferenceConfiguration.providerUrl;
    expect(aiReportSchema.safeParse(next).success).toBe(false);
    next.reportVersion="ollama-hypotheses-v4";expect(aiReportSchema.safeParse(next).success).toBe(true);
  });
  it("rejects fabricated citations, conflicting identities, duplicates and invalid sample counts",()=>{
    for(const mutate of [
      (r:typeof original)=>r.report.hypotheses[0].references[0].line=99999,
      (r:typeof original)=>r.report.hypotheses[0].references[0].filePath="invented.java",
      (r:typeof original)=>r.report.hypotheses[0].comparisonId="00000000-0000-0000-0000-000000000001",
      (r:typeof original)=>r.report.hypotheses[0].references.push(r.report.hypotheses[0].references[0]),
      (r:typeof original)=>r.input.evidence.baselineSamples.pop(),
      (r:typeof original)=>r.input.diff.files[0].filePath="../escape.java",
      (r:typeof original)=>r.input.evidence.baselineWorkflowUrl="https://evil.test/steal",
    ]){const changed=structuredClone(original);mutate(changed);expect(aiReportSchema.safeParse(changed).success).toBe(false);}
    expect(matchesAiContext(report,{...context,comparisonId:"00000000-0000-0000-0000-000000000001"})).toBe(false);
  });
  it("links immutable candidate lines and the previous filename for a renamed baseline",()=>{
    const ref=report.report.hypotheses[0].references[0];
    expect(codeReference(report,ref)?.url).toContain(`/blob/${report.input.diff.candidateSha}/`);
    expect(codeReference(report,ref)?.url).toContain(`#L${ref.line}`);
    const changed=structuredClone(original);const file=changed.input.diff.files[0];
    file.previousFilePath="src/Before Rename.java";
    const baseline=file.changedLines.find((l:{side:string})=>l.side==="BASELINE");
    const parsed=aiReportSchema.parse(changed);
    expect(codeReference(parsed,{filePath:file.filePath,side:"BASELINE",line:baseline.line})?.url).toContain(`/blob/${parsed.input.diff.baselineSha}/src/Before%20Rename.java#L${baseline.line}`);
  });
});
describe("AI presentation",()=>{
  it("renders server numbers and model warnings without claiming confirmed causes",()=>{
    const html=renderToStaticMarkup(<AiReportView report={report} currentAnalysisId={a.id}/>);
    expect(html).toContain("Unverified hypotheses");expect(html).toContain("NOT CONFIRMED");
    expect(html).toContain(String(a.result.baseline!.forkMeans.median));expect(html).toContain("inconclusive");
    expect(html).toContain("Such an experiment is inadequate");expect(html).toContain("not executed");
    expect(html).toContain("#L16");expect(html).not.toContain("Stored report snapshot:");
  });
  it("preserves empty reports and clearly distinguishes frozen policies",()=>{
    const changed=structuredClone(original);changed.report.hypotheses=[];changed.status="NO_HYPOTHESES";
    const html=renderToStaticMarkup(<AiReportView report={aiReportSchema.parse(changed)} currentAnalysisId="different"/>);
    expect(html).toContain("No hypotheses returned");expect(html).toContain("frozen evidence");
  });
  it("escapes model prose and changed code as text",()=>{
    const changed=structuredClone(original);changed.report.hypotheses[0].potentialCause="<script>alert('x')</script>";
    const ref=changed.report.hypotheses[0].references[0];
    changed.input.diff.files[0].changedLines.find((l:{side:string;line:number})=>l.side===ref.side&&l.line===ref.line).code="<iframe src='evil'>";
    const html=renderToStaticMarkup(<AiReportView report={aiReportSchema.parse(changed)}/>);
    expect(html).not.toContain("<script>");expect(html).toContain("&lt;script&gt;");expect(html).not.toContain("<iframe");expect(html).toContain("&lt;iframe");
  });
  it("shows archived reports in read-only mode and declines below-threshold generation",()=>{
    const page={items:[report],page:0,size:5,totalElements:1};
    const html=renderToStaticMarkup(<AiInvestigation initial={{kind:"ready",page}} context={context} analysis={a} localEnabled={false} outdated={false} pageHref={{}}/>);
    expect(html).toContain("Read-only mode");expect(html).toContain("disabled");expect(html).toContain("Investigation complete");
    const small=structuredClone(a);small.result.relativeChangePercent=0;
    const empty=renderToStaticMarkup(<AiInvestigation initial={{kind:"ready",page:{...page,items:[]}}} context={context} analysis={small} localEnabled outdated={false} pageHref={{}}/>);
    expect(empty).toContain("Insufficient evidence");expect(empty).toContain("No stored investigation");
  });
  it("maps backend failures without fabricating results",()=>{
    expect(aiFailure("OLLAMA_MODEL_UNAVAILABLE").status).toBe("ollama-unavailable");
    expect(aiFailure("NO_PRACTICAL_EFFECT").status).toBe("insufficient-evidence");
    expect(aiFailure("INVALID_AI_RESPONSE").status).toBe("failed");
    expect(aiFailure("OLLAMA_TIMEOUT").message).toContain("Check stored reports");
  });
  it("does not mistake failed archive reads for an empty database",()=>{
    const html=renderToStaticMarkup(<AiInvestigation initial={{kind:"error"}} context={context} analysis={a} localEnabled={false} outdated={false} pageHref={{}}/>);
    expect(html).toContain("Stored reports unavailable");expect(html).not.toContain("No stored investigation");
  });
});
function configure(){vi.stubEnv("REGRESSLAB_AI_UI_ENABLED","true");vi.stubEnv("REGRESSLAB_LOCAL_AI_CAPABILITY","a".repeat(64));vi.stubEnv("REGRESSLAB_LOCAL_AI_ORIGIN","http://127.0.0.1:3000");vi.stubEnv("REGRESSLAB_INGESTION_TOKEN","test-only-token-000000000000000000000");vi.stubEnv("REGRESSLAB_API_URL","http://127.0.0.1:8081");}
function request(extra:Record<string,string>={}){return new Request("http://127.0.0.1:3000/api/ai-investigations",{method:"POST",headers:{host:"127.0.0.1:3000",origin:"http://127.0.0.1:3000","sec-fetch-site":"same-origin","x-regresslab-local-ai":"a".repeat(64),...extra}});}
describe("protected local bridge",()=>{
  it("is disabled in standard startup and never fetches with missing attestation",async()=>{
    vi.stubEnv("REGRESSLAB_AI_UI_ENABLED","false");const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
    expect(localAiConfiguration().enabled).toBe(false);expect((await POST(request())).status).toBe(403);expect(fetch).not.toHaveBeenCalled();
    configure();expect((await POST(request({"x-regresslab-local-ai":"forged"}))).status).toBe(403);expect(fetch).not.toHaveBeenCalled();
  });
  it("requires exact local origin, fetch metadata and private capability",()=>{
    configure();expect(authorizedLocalUiRequest(request())).toBe(true);
    const internal=new Request("http://localhost:3000/api/ai-investigations",request());expect(authorizedLocalUiRequest(internal)).toBe(true);
    const variations:Record<string,string>[]=[{origin:"https://evil.test"},{host:"evil.test"},{"sec-fetch-site":"cross-site"},{"x-regresslab-local-ai":"é".repeat(64)}];
    for(const headers of variations)expect(authorizedLocalUiRequest(request(headers))).toBe(false);
    vi.stubEnv("REGRESSLAB_API_URL","https://remote.example");expect(localAiConfiguration().enabled).toBe(false);
  });
  it("bounds streams before JSON parsing",async()=>{
    expect(await boundedText(new Response("hello").body,5)).toBe("hello");
    await expect(boundedText(new Response("too large").body,2)).rejects.toThrow("Oversized");
    await expect(boundedText(new Response(new Uint8Array([255])).body,5)).rejects.toThrow();
  });
});


describe("durable AI jobs",()=>{
  const base={id:"11111111-1111-4111-8111-111111111111",comparisonId:report.comparisonId,analysisId:a.id,status:"QUEUED" as const,reportId:null,reused:false,errorCode:null,detail:null,createdAt:report.createdAt,updatedAt:report.createdAt};
  it("validates job state relationships and reference scope",()=>{
    expect(aiJobSchema.safeParse(base).success).toBe(true);
    expect(aiJobSchema.safeParse({...base,status:"SUCCEEDED"}).success).toBe(false);
    expect(aiJobSchema.safeParse({...base,status:"FAILED"}).success).toBe(false);
    expect(aiJobSchema.safeParse({...base,reused:true}).success).toBe(false);
  });
  it("renders queued work and preserves saved reports during failure",()=>{
    const page={items:[report],page:0,size:5,totalElements:1};
    const pending=renderToStaticMarkup(<AiInvestigation initial={{kind:"ready",page}} initialJob={base} context={context} analysis={a} localEnabled outdated={false} pageHref={{}}/>);
    expect(pending).toContain("Queued investigation");expect(pending).toContain("Generating report…");expect(pending).toContain("Unverified hypotheses");
    const failed=renderToStaticMarkup(<AiInvestigation initial={{kind:"ready",page}} initialJob={{...base,status:"FAILED",errorCode:"OLLAMA_UNAVAILABLE",detail:"Offline"}} context={context} analysis={a} localEnabled outdated={false} pageHref={{}}/>);
    expect(failed).toContain("Ollama unavailable");expect(failed).toContain("Unverified hypotheses");expect(failed).toContain("Existing reports remain available");
  });
  it("rejects malformed read-only job queries without contacting the backend",async()=>{
    const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
    expect((await GET(new Request("http://127.0.0.1:3000/api/ai-investigations?jobId=invalid"))).status).toBe(502);
    expect(fetch).not.toHaveBeenCalled();
  });
});
