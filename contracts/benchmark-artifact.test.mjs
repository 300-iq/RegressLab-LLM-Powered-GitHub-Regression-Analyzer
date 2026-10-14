import { readFile } from 'node:fs/promises';
import test from 'node:test';
import assert from 'node:assert/strict';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const schema = JSON.parse(await readFile(new URL('./benchmark-artifact.schema.json', import.meta.url)));
const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);
const validate = ajv.compile(schema);

// Synthetic contract fixture only; never loaded into the database or application.
const fixture = () => ({
  schemaVersion: 1, dataOrigin: 'DEMO', repository: { githubId: 1, fullName: 'fixture/demo' },
  pullRequestNumber: 1, workflow: { runId: 1, runAttempt: 1 },
  baselineSha: 'a'.repeat(40), candidateSha: 'b'.repeat(40), measuredAt: '2026-10-13T12:00:00Z',
  environment: { runner: 'test fixture', os: 'Linux', architecture: 'x86_64', cpu: 'fixture', javaVersion: '21', jmhVersion: '1.37' },
  benchmarks: [{
    name: 'dev.regresslab.Example.operation', parameters: {}, metric: 'average_time', unit: 'ns/op', direction: 'LOWER',
    configuration: { warmupIterations: 3, measurementIterations: 5, forkCount: 3, warmupTimeMs: 1000, measurementTimeMs: 1000, jvmArgs: [] },
    baseline: forks(), candidate: forks(),
  }],
});
function forks() {
  return Array.from({ length: 3 }, (_, forkIndex) => ({ forkIndex, observations: Array.from({ length: 5 }, (_, iterationIndex) => ({ iterationIndex, value: 10 + iterationIndex })) }));
}

test('valid explicitly labeled synthetic contract fixture', () => assert.equal(validate(fixture()), true, JSON.stringify(validate.errors)));
for (const [name, mutate] of [
  ['unknown payload fields', x => { x.command = 'execute me'; }],
  ['unsupported schema version', x => { x.schemaVersion = 2; }],
  ['oversized benchmark list', x => { x.benchmarks = Array(21).fill(x.benchmarks[0]); }],
  ['missing provenance', x => { delete x.workflow; }],
  ['wrong measurement unit', x => { x.benchmarks[0].unit = 'ms'; }],
  ['invalid commit identifier', x => { x.candidateSha = 'main'; }],
  ['non-positive observation', x => { x.benchmarks[0].baseline[0].observations[0].value = 0; }],
  ['non-finite observation', x => { x.benchmarks[0].candidate[0].observations[0].value = Infinity; }],
  ['invalid timestamp', x => { x.measuredAt = 'yesterday'; }],
]) {
  test(`rejects ${name}`, () => { const value = fixture(); mutate(value); assert.equal(validate(value), false); });
}
