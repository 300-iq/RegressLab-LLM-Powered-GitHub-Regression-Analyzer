// Explicitly synthetic development data; never measured benchmark outcomes.
// Regenerate fixtures with: node backend/fixtures/generate-demo.mjs
import { writeFile } from 'node:fs/promises';

const config = { warmupIterations: 3, measurementIterations: 5, forkCount: 3, warmupTimeMs: 1000, measurementTimeMs: 1000, jvmArgs: ['-Xms256m', '-Xmx256m'] };
function samples(mean, scale) {
  return Array.from({ length: 15 }, (_, index) => {
    const forkIndex = Math.floor(index / 5), iterationIndex = index % 5;
    return { forkIndex, iterationIndex, value: mean + [-5, 0, 6][forkIndex] * scale + [-3, 1, 0, 2, -1][iterationIndex] * scale };
  });
}
for (const candidate of [false, true]) {
  const artifact = {
    schemaVersion: 2, artifactId: candidate ? 'demo-map-lookup-candidate-v1' : 'demo-map-lookup-baseline-v1', dataOrigin: 'DEMO',
    repository: { githubId: 1, owner: 'regresslab-demo', name: 'java-benchmarks' },
    pullRequest: { number: 42, title: 'DEMO fixture: investigate a synthetic map lookup change', state: 'OPEN', baselineSha: 'a'.repeat(40), candidateSha: 'b'.repeat(40) },
    run: { workflowRunId: 42001, runAttempt: 1, commitSha: (candidate ? 'b' : 'a').repeat(40), branch: candidate ? 'demo/map-lookup' : 'main',
      measuredAt: candidate ? '2026-10-13T12:05:00Z' : '2026-10-13T12:00:00Z', status: 'COMPLETE',
      environment: { runner: 'DEMO synthetic runner', os: 'Linux (synthetic)', architecture: 'x86_64', cpu: 'DEMO synthetic CPU', javaVersion: '21.0.9', jmhVersion: '1.37' } },
    benchmarks: [
      { identifier: 'dev.regresslab.MapLookup.v1', metricName: 'average_time', unit: 'ns/op', direction: 'LOWER', parameters: { entries: '10000' }, configuration: config, samples: samples(candidate ? 343 : 290, 1) },
      { identifier: 'dev.regresslab.JsonEncode.v1', metricName: 'throughput', unit: 'ops/s', direction: 'HIGHER', parameters: { payloadBytes: '4096' }, configuration: config, samples: samples(candidate ? 198000 : 215000, 150) },
    ],
  };
  await writeFile(new URL(candidate ? './demo-candidate.v2.json' : './demo-baseline.v2.json', import.meta.url), JSON.stringify(artifact, null, 2) + '\n');
}
