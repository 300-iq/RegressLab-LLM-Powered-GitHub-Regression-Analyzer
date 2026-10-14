// Trusted local administration only. ZIPs remain inert bytes. GitHub identity and
// original ZIP digests are checked by the server before the atomic pair import.
import { readFile, stat } from 'node:fs/promises';
const [run, baselineId, candidateId, baselinePath, candidatePath] = process.argv.slice(2);
if (![run, baselineId, candidateId].every(value => /^\d+$/.test(value ?? '') && Number.isSafeInteger(Number(value)) && Number(value)>0) || !baselinePath || !candidatePath)
  throw new Error('Usage: node scripts/import-github.mjs RUN_ID BASELINE_ARTIFACT_ID CANDIDATE_ARTIFACT_ID BASELINE.zip CANDIDATE.zip');
const base = new URL(process.env.REGRESSLAB_API_URL ?? 'http://127.0.0.1:8080');
if (base.protocol!=='http:' || !['127.0.0.1','[::1]'].includes(base.hostname) || base.username || base.password || base.search || base.hash)
  throw new Error('Administrative imports require a loopback HTTP API.');
const token = process.env.REGRESSLAB_INGESTION_TOKEN;
if (!token || token.length<32 || token.length>256) throw new Error('Set the protected local ingestion token.');
const sizes=await Promise.all([baselinePath,candidatePath].map(async path => (await stat(path)).size));
if (sizes.some(size=>size<1 || size>524288) || sizes[0]+sizes[1]>524288) throw new Error('Archive pair must be at most 512 KiB.');
const archives=await Promise.all([baselinePath,candidatePath].map(async path => (await readFile(path)).toString('base64').match(/.{1,1000}/g)));
async function request(path, body) {
  const response=await fetch(new URL(path,base),{ method:body===undefined?'GET':'POST', signal:AbortSignal.timeout(120000),
    headers:body===undefined?{}:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();
  if(!response.ok) throw new Error(`${response.status}: ${result.code ?? 'API_ERROR'}: ${result.detail ?? 'Request failed'}`);
  return result;
}
const imported=await request('/api/v1/dev/github-imports',{workflowRunId:Number(run),baselineArtifactId:Number(baselineId),candidateArtifactId:Number(candidateId),baselineArchiveChunks:archives[0],candidateArchiveChunks:archives[1]});
const baseline=await request(`/api/v1/runs/${imported.baseline.runId}`), candidate=await request(`/api/v1/runs/${imported.candidate.runId}`);
const comparisons=[];
if(baseline.run.status==='COMPLETE' && candidate.run.status==='COMPLETE')
  for(const benchmark of baseline.benchmarks) comparisons.push(await request('/api/v1/comparisons',{baselineRunId:baseline.run.id,candidateRunId:candidate.run.id,benchmarkCaseId:benchmark.benchmarkCase.id}));
const audit=await request(`/api/v1/runs/${baseline.run.id}/github-audit`);
console.log(JSON.stringify({notice:'Real executed Java measurements from a public demonstration workload. GitHub origin metadata checked; no statistical significance or measurement attestation.',imported,overview:await request('/api/v1/overview'),pullRequest:(await request(`/api/v1/pull-requests/${imported.baseline.pullRequestId}`)).pullRequest,baseline,candidate,comparisons,audit},null,2));
