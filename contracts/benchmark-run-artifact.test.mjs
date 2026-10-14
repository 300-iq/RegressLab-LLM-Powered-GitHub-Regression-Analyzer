import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import test from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv);
const schema = JSON.parse(await readFile(new URL('./benchmark-run-artifact.v2.schema.json', import.meta.url)));
const validate = ajv.compile(schema);
const baseline = JSON.parse(await readFile(new URL('../backend/fixtures/demo-baseline.v2.json', import.meta.url)));
const candidate = JSON.parse(await readFile(new URL('../backend/fixtures/demo-candidate.v2.json', import.meta.url)));
test('both explicitly synthetic development fixtures conform to v2', () => {
  for (const fixture of [baseline, candidate]) { assert.equal(fixture.dataOrigin, 'DEMO'); assert.equal(validate(fixture), true, JSON.stringify(validate.errors)); }
});
test('rejects unsupported units and metric directions', () => {
  const fixture = structuredClone(baseline); fixture.benchmarks[0].unit = 'ops/s'; assert.equal(validate(fixture), false);
});
test('failed runs cannot contain completed observations', () => {
  const fixture = structuredClone(baseline); fixture.run.status = 'FAILED'; assert.equal(validate(fixture), false);
  fixture.benchmarks = []; assert.equal(validate(fixture), true, JSON.stringify(validate.errors));
});
test('unknown fields and unsupported versions are rejected', () => {
  const fixture = structuredClone(baseline); fixture.script = 'never execute'; assert.equal(validate(fixture), false);
  delete fixture.script; fixture.schemaVersion = 3; assert.equal(validate(fixture), false);
});
test('actual GitHub Actions baseline and candidate artifacts conform to v2', async () => {
  for (const arm of ['baseline','candidate']) {
    const artifact=JSON.parse(await readFile(new URL(`./fixtures/github-${arm}.v2.json`,import.meta.url)));
    assert.equal(artifact.dataOrigin,'REAL');
    assert.equal(validate(artifact),true,JSON.stringify(validate.errors));
    assert.equal(artifact.benchmarks.length,2);
    for(const benchmark of artifact.benchmarks) assert.equal(benchmark.samples.length,15);
  }
});
