import { aiReportSchema, matchesAiContext, aiJobSchema, type AiContext } from "./ai-contracts";
import { pageSchema } from "./contracts";
import { ApiError, readApi, requireId } from "./results";
export async function getAiReports(context:AiContext,page=0) {
  const result=await readApi(`/comparisons/${requireId(context.comparisonId)}/ai-investigations?page=${page}&size=5`,pageSchema(aiReportSchema));
  if(result.page!==page || result.items.some(r=>!matchesAiContext(r,context)))throw new ApiError("invalid");
  return result;
}

export async function getLatestAiJob(context:AiContext,analysisId:string) {
  const page=await readApi(`/comparisons/${requireId(context.comparisonId)}/ai-investigation-jobs?size=5`,pageSchema(aiJobSchema));
  if(page.items.some(j=>j.comparisonId!==context.comparisonId))throw new ApiError("invalid");
  return page.items.find(j=>j.analysisId===analysisId)??null;
}
