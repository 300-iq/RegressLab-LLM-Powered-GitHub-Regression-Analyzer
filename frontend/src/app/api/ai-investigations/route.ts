import { z } from "zod";
import { authorizedLocalUiRequest, boundedText } from "@/lib/local-ai";
import { aiFailure, aiReportSchema, aiJobSchema, matchesJob } from "@/lib/ai-contracts";
import { getAnalysis, getComparison } from "@/lib/results";
import { comparisonEvidence } from "@/lib/workspace";

export const runtime="nodejs";
const requestSchema=z.object({comparisonId:z.uuid(),analysisId:z.uuid()}).strict();
export async function POST(request:Request) {
  const fail=(code:string,status:number)=>Response.json(aiFailure(code),{status,headers:{"Cache-Control":"no-store"}});
  if(!authorizedLocalUiRequest(request))return fail("LOCAL_UI_DISABLED",403);
  if(!/^application\/json(?:;\s*charset=utf-8)?$/i.test(request.headers.get("content-type")??"") || request.headers.has("content-encoding"))return fail("INVALID_REQUEST",415);
  let input:z.infer<typeof requestSchema>;
  try{const text=await boundedText(request.body,2048);
    if((text.match(/"comparisonId"\s*:/g)??[]).length!==1 || (text.match(/"analysisId"\s*:/g)??[]).length!==1)throw Error("Invalid fields");
    input=requestSchema.parse(JSON.parse(text));
  }catch{return fail("INVALID_REQUEST",400);}
  try {
    const [comparison,analysis]=await Promise.all([getComparison(input.comparisonId),getAnalysis(input.analysisId)]);
    const evidence=await comparisonEvidence(comparison);
    if(analysis.comparisonId!==comparison.id || comparison.dataOrigin!=="REAL" || analysis.dataOrigin!=="REAL" || analysis.provenance!=="GITHUB_METADATA")return fail("CONFLICT",409);
    const {getPr}=await import("@/lib/results");const pr=(await getPr(evidence.candidate.run.pullRequestId)).pullRequest;
    if(pr.baselineSha!==evidence.baseline.run.commitSha || pr.candidateSha!==evidence.candidate.run.commitSha)return fail("CONFLICT",409);
    const response=await fetch(`${process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080"}/api/v1/comparisons/${comparison.id}/ai-investigations`,{
      method:"POST",cache:"no-store",signal:AbortSignal.timeout(10000),
      headers:{"Content-Type":"application/json",Authorization:`Bearer ${process.env.REGRESSLAB_INGESTION_TOKEN}`},body:JSON.stringify({analysisId:analysis.id}),
    });
    const raw=JSON.parse(await boundedText(response.body,1048576));
    if(!response.ok)return fail(typeof raw.code==="string"?raw.code:"API_ERROR",response.status);
    if(response.status!==202)return fail("INVALID_REPORT_RESPONSE",502);
    const job=aiJobSchema.parse(raw);
    if(!matchesJob(job,comparison.id,analysis.id))return fail("INVALID_REPORT_RESPONSE",502);
    return Response.json({kind:"pending",job},{status:202,headers:{"Cache-Control":"no-store"}});
  }catch{return fail("CONNECTION_ERROR",502);}
}

// Read-only status/report polling. No bearer and no model invocation; standard read-only mode may resume jobs.
export async function GET(request:Request) {
  try {
    const query=z.object({jobId:z.uuid(),comparisonId:z.uuid(),analysisId:z.uuid()}).strict().parse(Object.fromEntries(new URL(request.url).searchParams));
    const {readApi}=await import("@/lib/results");
    const job=await readApi(`/ai-investigation-jobs/${query.jobId}`,aiJobSchema);
    if(!matchesJob(job,query.comparisonId,query.analysisId))throw Error("Scope mismatch");
    if(job.status!=="SUCCEEDED")return Response.json({kind:job.status==="FAILED"?"failed":"pending",job},{headers:{"Cache-Control":"no-store"}});
    const report=await readApi(`/ai-investigations/${job.reportId}`,aiReportSchema);
    if(report.comparisonId!==job.comparisonId || report.input.evidence.analysis.id!==job.analysisId)throw Error("Report mismatch");
    return Response.json({kind:"complete",job,report,reused:job.reused},{headers:{"Cache-Control":"no-store"}});
  }catch{return Response.json(aiFailure("CONNECTION_ERROR"),{status:502,headers:{"Cache-Control":"no-store"}});}
}
