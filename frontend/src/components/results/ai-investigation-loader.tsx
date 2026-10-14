import type { InvestigationData } from "@/lib/investigation-data";
import { getAiReports, getLatestAiJob } from "@/lib/ai-results";
import { attempt, pageNumber, queryHref } from "@/lib/results";
import { localAiConfiguration } from "@/lib/local-ai";
import { AiInvestigation } from "./ai-investigation";

export async function aiInvestigationPanel(data:InvestigationData) {
  if(!data.evidence || !data.comparison)return null;
  const e=data.evidence, context={comparisonId:data.comparison.id,repository:data.repo.fullName,prNumber:data.pr.number,
    baselineSha:e.baseline.run.commitSha,candidateSha:e.candidate.run.commitSha,benchmarkIdentifier:e.benchmark.identifier,unit:e.benchmark.unit,direction:e.benchmark.direction};
  const page=pageNumber(data.query,"aiPage"), loaded=await attempt(()=>getAiReports(context,page));
  const analysis=data.analysisState?.kind==="ready"?data.analysisState.analysis:null;
  const latest=analysis&&page===0?await attempt(()=>getLatestAiJob(context,analysis.id)):null;
  const initial=loaded.kind==="ready"?{kind:"ready" as const,page:loaded.value}:{kind:"error" as const};
  const href=(n:number)=>queryHref(data.path,data.query,{comparison:context.comparisonId,aiPage:n})+"#ai-investigation";
  return <AiInvestigation key={`${context.comparisonId}:${page}:${data.analysisState?.kind==="ready"?data.analysisState.analysis.id:"none"}`} initial={initial} context={context}
    analysis={data.analysisState?.kind==="ready"?data.analysisState.analysis:null} initialJob={latest?.kind==="ready"?latest.value:null} localEnabled={localAiConfiguration().enabled} outdated={data.outdated}
    pageHref={{previous:page>0?href(page-1):undefined,next:loaded.kind==="ready" && (page+1)*loaded.value.size<loaded.value.totalElements?href(page+1):undefined}}/>;
}
export async function AiInvestigationLoader({data}:{data:InvestigationData}) { return aiInvestigationPanel(data); }
