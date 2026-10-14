// Optional local-development importer. Reads static, explicitly synthetic fixtures;
// never executes uploaded artifact contents and never sends tokens to GitHub.
import { readFile } from 'node:fs/promises';

const base = new URL(process.env.REGRESSLAB_API_URL ?? 'http://127.0.0.1:8080');
if (base.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(base.hostname)) throw new Error('Fixture imports require a loopback HTTP API.');
const token = process.env.REGRESSLAB_INGESTION_TOKEN;
if (!token || token.length < 32) throw new Error('Set REGRESSLAB_INGESTION_TOKEN to the configured local bearer token.');

async function request(path, body) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? 'GET' : 'POST', signal: AbortSignal.timeout(15000),
    headers: body === undefined ? {} : { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body,
  });
  const json = await response.json();
  if (!response.ok) throw new Error(`${response.status}: ${json.code ?? 'API_ERROR'}: ${json.detail ?? 'Request failed'}`);
  return json;
}
const baseline = await request('/api/v1/dev/artifacts', await readFile(new URL('../backend/fixtures/demo-baseline.v2.json', import.meta.url)));
const candidate = await request('/api/v1/dev/artifacts', await readFile(new URL('../backend/fixtures/demo-candidate.v2.json', import.meta.url)));
const run = await request(`/api/v1/runs/${encodeURIComponent(baseline.runId)}`);
const comparisons = [];
for (const benchmark of run.benchmarks) comparisons.push(await request('/api/v1/comparisons', JSON.stringify({ baselineRunId: baseline.runId, candidateRunId: candidate.runId, benchmarkCaseId: benchmark.benchmarkCase.id })));
const caseId = run.benchmarks[0].benchmarkCase.id;
const repositoryId = baseline.repositoryId, pullRequestId = baseline.pullRequestId;
const samples = await request(`/api/v1/runs/${baseline.runId}/cases/${caseId}/samples?size=5`);
const history = await request(`/api/v1/cases/${caseId}/history?origin=DEMO`);
const detail = await request(`/api/v1/pull-requests/${pullRequestId}`);
console.log(JSON.stringify({
  notice: 'DEMO: synthetic development fixtures persisted through the protected local API. Not measured outcomes or verified GitHub resources.',
  repositoryId, pullRequestId, baselineRunId: baseline.runId, candidateRunId: candidate.runId,
  overview: await request('/api/v1/overview'), samplePage: samples, historyTotal: history.totalElements,
  persistedPullRequest: detail.pullRequest, comparisons,
}, null, 2));
