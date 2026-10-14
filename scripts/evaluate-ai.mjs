// Trusted local evaluation administration. Inputs are captures of genuine imports,
// never executable code. No retry hides a failed or invalid model response.
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
const base = new URL(process.env.REGRESSLAB_API_URL ?? 'http://127.0.0.1:8080');
if (base.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(base.hostname)
    || base.username || base.password || base.search || base.hash || base.pathname !== '/')
  throw new Error('Evaluation requires a direct loopback backend origin.');
const token = process.env.REGRESSLAB_INGESTION_TOKEN;
if (!token || token.length < 32 || token.length > 256) throw new Error('Configure the protected local bearer token.');
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
async function request(path, body) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? 'GET' : 'POST', signal: AbortSignal.timeout(330000),
    headers: body === undefined ? {} : { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  let bytes = 0; const chunks = [];
  for await (const chunk of response.body) { bytes += chunk.byteLength; if (bytes > 262144) throw new Error('Oversized API response'); chunks.push(chunk); }
  return { status: response.status, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) };
}
const files = process.argv.slice(2);
const attempt = process.env.REGRESSLAB_AI_EVALUATION_LABEL ?? new Date().toISOString().replace(/[^0-9A-Za-z]/g, '');
if (!/^[A-Za-z0-9_-]{1,80}$/.test(attempt)) throw new Error('Invalid evaluation label.');
if (!files.length) throw new Error('Usage: node scripts/evaluate-ai.mjs path/to/import.json [more imports]');
for (const file of files) {
  const imported = JSON.parse(await readFile(file, 'utf8')); const evaluations = [];
  if (imported.pullRequest?.dataOrigin !== 'REAL' || !Array.isArray(imported.comparisons)) throw new Error('Genuine REAL imported comparisons required.');
  for (const comparison of imported.comparisons) {
    if (!uuid(comparison.id)) throw new Error('Invalid comparison UUID');
    const analysis = await request(`/api/v1/comparisons/${comparison.id}/analysis`, {});
    if (analysis.status !== 200 || !uuid(analysis.body.id)) throw new Error(`Analysis failed: ${analysis.status}`);
    const label = analysis.body.result.benchmarkIdentifier.endsWith('.countByCategory') ? 'count' : 'distinct';
    await writeFile(join(dirname(file), `${label}-analysis-${attempt}.json`), JSON.stringify(analysis.body, null, 2) + '\n', { flag: 'wx' });
    console.log(`PR #${imported.pullRequest.number} ${label}: requesting one investigation job (unchanged reports may be reused)`);
    let result;
    try {
      result = await request(`/api/v1/comparisons/${comparison.id}/ai-investigations`, { analysisId: analysis.body.id });
      if (result.status === 202) {
        const jobId=result.body.id;
        for(let poll=0;poll<240;poll++) {
          const status=await request(`/api/v1/ai-investigation-jobs/${jobId}`);
          if(status.body.status==='SUCCEEDED') {const saved=await request(`/api/v1/ai-investigations/${status.body.reportId}`);result={status:202,jobStatus:'SUCCEEDED',reused:status.body.reused,reportReadStatus:saved.status,body:saved.body};break;}
          if(status.body.status==='FAILED') {result={status:202,jobStatus:'FAILED',body:{code:status.body.errorCode,detail:status.body.detail,job:status.body}};break;}
          await new Promise(resolve=>setTimeout(resolve,2000));
        }
      }
      if (result.status === 201 || result.jobStatus === 'SUCCEEDED') {
        if (!uuid(result.body.id)) throw new Error('Invalid report UUID');
        const stored = await request(`/api/v1/ai-investigations/${result.body.id}`);
        if (stored.status !== 200 || !isDeepStrictEqual(stored.body, result.body)) throw new Error('Persisted report readback differs');
      }
    } catch (error) { result = { status: 'CLIENT_FAILURE', body: { message: error.message } }; }
    await writeFile(join(dirname(file), `${label}-report-${attempt}.json`), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
    evaluations.push({ benchmark: analysis.body.result.benchmarkIdentifier, comparisonId: comparison.id,
      measuredRelativeChangePercent: analysis.body.result.relativeChangePercent, classification: analysis.body.result.classification,
      reportStatus: result.status, jobStatus: result.jobStatus ?? null, reused:result.reused ?? null, reportId: result.body.id ?? null, code: result.body.code ?? null });
    console.log(`PR #${imported.pullRequest.number} ${label}: ${result.status}, ${result.body.status ?? result.body.code ?? 'client failure'}`);
  }
  await writeFile(join(dirname(file), `evaluation-${attempt}.json`), JSON.stringify(evaluations, null, 2) + '\n', { flag: 'wx' });
}
