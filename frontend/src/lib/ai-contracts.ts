import { z } from "zod";
import { analysisSchema, sampleSchema, configurationSchema, environmentSchema } from "./contracts";

const id = z.uuid(), hash = z.string().regex(/^[0-9a-f]{64}$/), sha = z.string().regex(/^[0-9a-f]{40}$/);
const path = z.string().min(1).max(300).refine(p => !p.startsWith("/") && !p.includes("\\") && !/[\u0000-\u001f\u007f]/.test(p) && p.split("/").every(s => s && s !== "." && s !== ".."));
const side = z.enum(["BASELINE", "CANDIDATE"]);
const line = z.number().int().positive().max(2147483647);
const prose = z.string().min(1).max(2000);
const reference = z.object({ filePath: path, side, line }).strict();
export const aiReportSchema = z.object({
  id, comparisonId: id, reportVersion: z.string().max(50), inputSha256: hash,
  model: z.string().max(100), modelDigest: hash,
  status: z.enum(["UNVERIFIED_HYPOTHESES", "NO_HYPOTHESES"]), createdAt: z.iso.datetime({ offset: true }),
  input: z.object({
    diff: z.object({ repository: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9_.-]{1,100}$/),
      githubRepositoryId: z.number().int().positive().max(Number.MAX_SAFE_INTEGER), pullRequestNumber: z.number().int().positive(),
      baselineSha: sha, candidateSha: sha, files: z.array(z.object({ filePath: path, previousFilePath: path,
        patch: z.string().max(6000), changedLines: z.array(z.object({side,line,code:z.string().max(6000)}).strict()).max(6000),
      }).strict()).max(20),
    }).strict(),
    evidence: z.object({ analysis: analysisSchema,
      baselineWorkflowUrl: z.string(), candidateWorkflowUrl: z.string(),
      baselineSamples: z.array(sampleSchema).min(1).max(100), candidateSamples: z.array(sampleSchema).min(1).max(100),
      baselineEnvironmentSha256: hash, candidateEnvironmentSha256: hash,
      conditions: z.object({ baselineEnvironment: environmentSchema, candidateEnvironment: environmentSchema,
        baselineParameters: z.record(z.string(),z.string()), candidateParameters: z.record(z.string(),z.string()),
        baselineConfiguration: configurationSchema, candidateConfiguration: configurationSchema,
        inferenceConfiguration: z.object({providerUrl:z.string().url().refine(value=>{const match=/^http:\/\/(?:localhost|127\.0\.0\.1|\[::1\]):([0-9]{1,5})\/?$/.exec(value);return !!match&&Number(match[1])>=1&&Number(match[1])<=65535;}).optional(),model:z.string().min(1).max(100),options:z.object({temperature:z.literal(0),seed:z.literal(20261014),num_ctx:z.number().int().min(8192).max(16384),num_predict:z.literal(4096)}).strict(),think:z.literal(false),stream:z.literal(false),keep_alive:z.literal(0),timeoutSeconds:z.number().int().min(1).max(300),promptBudgetBytes:z.number().int().positive().max(12000)}).strict().optional(),
      }).strict().nullable(),
    }).strict(),
  }).strict(),
  report: z.object({ hypotheses: z.array(z.object({ potentialCause: prose, references: z.array(reference).min(1).max(10),
    comparisonId: id, technicalExplanation: prose, verificationExperiment: prose, uncertainty: prose,
  }).strict()).max(5), missingEvidence: z.array(prose).min(1).max(10) }).strict(),
  limitations: z.array(z.string().max(3000)).min(1).max(30),
}).strict().superRefine((r,ctx) => {
  const e=r.input.evidence, d=r.input.diff, a=e.analysis;
  const invalid=()=>ctx.addIssue({code:"custom",message:"AI report does not match its supplied evidence"});
  if(e.conditions?.inferenceConfiguration && e.conditions.inferenceConfiguration.model!==r.model)invalid();
  if(r.reportVersion==="ollama-hypotheses-v5" && !e.conditions?.inferenceConfiguration?.providerUrl)invalid();
  if (a.comparisonId !== r.comparisonId || a.dataOrigin !== "REAL" || a.provenance !== "GITHUB_METADATA" || a.result.classification === "INVALID_COMPARISON") invalid();
  if ((r.status === "NO_HYPOTHESES") !== (r.report.hypotheses.length === 0)) invalid();
  if (new Set(d.files.map(f=>f.filePath)).size !== d.files.length) invalid();
  for(const [arm,samples] of [[a.result.baseline,e.baselineSamples],[a.result.candidate,e.candidateSamples]] as const)
    if (!arm || arm.rawMeasurements.count !== samples.length || new Set(samples.map(s=>`${s.forkIndex}:${s.iterationIndex}`)).size !== samples.length) invalid();
  for(const url of [e.baselineWorkflowUrl,e.candidateWorkflowUrl])
    if (!new RegExp(`^https://github\\.com/${d.repository.replace(/[.*+?^${}()|[\]\\]/g,"\\$&")}/actions/runs/[1-9][0-9]*$`).test(url)) invalid();
  for(const h of r.report.hypotheses) {
    if(h.comparisonId !== r.comparisonId || new Set(h.references.map(ref=>`${ref.filePath}:${ref.side}:${ref.line}`)).size!==h.references.length) invalid();
    for(const ref of h.references) if(!d.files.find(f=>f.filePath===ref.filePath)?.changedLines.some(l=>l.side===ref.side && l.line===ref.line)) invalid();
  }
});
export type AiReport = z.infer<typeof aiReportSchema>;
export type AiContext = { comparisonId:string; repository:string; prNumber:number; baselineSha:string; candidateSha:string; benchmarkIdentifier:string; unit:string; direction:string };
export function matchesAiContext(report:AiReport, context:AiContext) {
  const d=report.input.diff, r=report.input.evidence.analysis.result;
  return report.comparisonId === context.comparisonId && d.repository === context.repository && d.pullRequestNumber === context.prNumber &&
    d.baselineSha === context.baselineSha && d.candidateSha === context.candidateSha && r.benchmarkIdentifier === context.benchmarkIdentifier && r.unit === context.unit && r.direction === context.direction;
}
export function codeReference(report:AiReport, ref:AiReport["report"]["hypotheses"][number]["references"][number]) {
  const file=report.input.diff.files.find(f=>f.filePath===ref.filePath);
  const changed=file?.changedLines.find(l=>l.side===ref.side && l.line===ref.line);
  if(!file || !changed) return null;
  const baseline=ref.side === "BASELINE", d=report.input.diff;
  const filePath=baseline?file.previousFilePath:file.filePath;
  return { code:changed.code, filePath, url:`https://github.com/${d.repository}/blob/${baseline?d.baselineSha:d.candidateSha}/${filePath.split("/").map(encodeURIComponent).join("/")}#L${ref.line}` };
}
export type AiFailure = { kind:"failure"; status:"ollama-unavailable"|"insufficient-evidence"|"failed"; message:string; code:string };
export function aiFailure(code:string):AiFailure {
  if(["OLLAMA_UNAVAILABLE","OLLAMA_MODEL_UNAVAILABLE"].includes(code)) return {kind:"failure",status:"ollama-unavailable",code,message:"The local Ollama server or configured model is unavailable. Existing reports remain available."};
  if(["NO_PRACTICAL_EFFECT","CONFLICT","REPOSITORY_NOT_AUTHORIZED"].includes(code)) return {kind:"failure",status:"insufficient-evidence",code,message:"The backend declined this evidence or PR snapshot. An observed effect must reach the stored threshold and use authorized, compatible, current measurements. This does not prove that performance is unchanged."};
  return {kind:"failure",status:"failed",code,message:code==="INVALID_AI_RESPONSE"?"The model response failed validation. No completed report was stored; invented or invalid references are not displayed.":
    code==="INVALID_REPORT_RESPONSE"?"The returned report did not match the selected evidence and was not displayed. Check stored reports before another attempt; the backend may have completed its write.":
    code==="OLLAMA_TIMEOUT"?"Ollama exceeded its generation deadline. Check stored reports before another attempt.":code==="AI_BUSY"?"Another investigation is running. Check stored reports and retry later.":code==="LOCAL_UI_DISABLED"?"Generation requires the protected local AI launcher. Stored reports are available in read-only mode.":"The investigation request could not be completed. Check stored reports before trying again; no replacement result has been generated."};
}

export const aiJobSchema=z.object({id:z.uuid(),comparisonId:z.uuid(),analysisId:z.uuid(),status:z.enum(["QUEUED","RUNNING","SUCCEEDED","FAILED"]),
  reportId:z.uuid().nullable(),reused:z.boolean(),errorCode:z.string().max(100).nullable(),detail:z.string().max(2000).nullable(),createdAt:z.iso.datetime({offset:true}),updatedAt:z.iso.datetime({offset:true})}).strict().superRefine((j,ctx)=>{
    if((j.status==="SUCCEEDED")!==(j.reportId!==null) || (j.status==="FAILED")!==(j.errorCode!==null&&j.detail!==null) || (j.reused&&j.status!=="SUCCEEDED") || (j.status!=="FAILED"&&(j.errorCode!==null||j.detail!==null)))ctx.addIssue({code:"custom",message:"Invalid job status relationship"});
  });
export type AiJob=z.infer<typeof aiJobSchema>;
export function matchesJob(job:AiJob,comparisonId:string,analysisId:string) { return job.comparisonId===comparisonId && job.analysisId===analysisId; }
