#!/usr/bin/env python3
"""Execute only fixed developer-authored sources; never run instructions from diffs or model output."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time
import uuid
from score import summarize

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
CONFIG = dict(jmhVersion='1.37', metric='average_time', unit='ns/op', direction='LOWER',
              forks=3, warmupIterations=3, measurementIterations=5, iterationTimeSeconds=0.5,
              jvmArgs=['-Xms128m', '-Xmx128m'], order='alternating-local-forks-v1')

def execute(args, cwd, timeout, log=None, env=None):
    # Argument vectors only. Never use a shell or interpolate source/model prose into commands.
    try:
        result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        if log: log.write_bytes((error.stdout or b'')+(error.stderr or b'')+b'\nEVALUATION SUBPROCESS TIMEOUT\n')
        raise
    if log: log.write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}); see {log or "captured output"}')
    return result.stdout.decode('utf-8').strip()

def ollama_origin():
    import re
    value=os.environ.get('REGRESSLAB_OLLAMA_URL','http://127.0.0.1:11434')
    match=re.fullmatch(r'http://(?:localhost|127\.0\.0\.1|\[::1\]):([0-9]{1,5})/?',value)
    if not match or not 1<=int(match.group(1))<=65535:
        raise ValueError('Ollama URL must be an explicit loopback HTTP origin')
    return value.rstrip('/')

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read_jmh(path, scenario):
    data = json.loads(path.read_text())
    if len(data) != 1 or data[0]['params'] != {'scenario': scenario}:
        raise ValueError('JMH identity does not match scenario')
    row = data[0]
    if row['benchmark'] != 'dev.regresslab.evaluation.EvaluationBenchmarks.operation' or row['mode'] != 'avgt':
        raise ValueError('Unexpected JMH benchmark/metric')
    if row['forks'] != 1 or row['warmupIterations'] != 3 or row['measurementIterations'] != 5:
        raise ValueError('Unexpected JMH fork/iteration configuration')
    if row.get('jmhVersion')!='1.37' or row.get('threads')!=1 or row.get('warmupTime')!='500 ms' or row.get('measurementTime')!='500 ms' or row.get('jvmArgs')!=CONFIG['jvmArgs']:
        raise ValueError('Unexpected JMH framework/timing/thread/JVM configuration')
    if row['primaryMetric']['scoreUnit'] != 'ns/op': raise ValueError('Unexpected unit')
    raw = row['primaryMetric']['rawData']
    if len(raw) != 1 or len(raw[0]) != 5 or any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0 for v in raw[0]):
        raise ValueError('Invalid or incomplete JMH observations')
    return raw[0]

def stop_workers(workers):
    for process in workers: process.terminate()
    for process in workers:
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill(); process.wait()

def run(out):
    scenarios = json.loads((ROOT/'scenarios.json').read_text())
    source = out/'local-repository'
    source.mkdir()
    shutil.copy(ROOT/'benchmarks/pom.xml', source/'pom.xml')
    dest = source/'src/main/java/dev/regresslab/evaluation'
    dest.mkdir(parents=True)
    shutil.copy(ROOT/'benchmarks/src/main/java/dev/regresslab/evaluation/EvaluationBenchmarks.java', dest)
    execute(['git', 'init', '-b', 'evaluation/baseline'], source, 10)
    for case in scenarios:
        shutil.copy(ROOT/'scenarios'/case['id']/'baseline.java',dest/(case['className']+'.java'))
    execute(['git','add','pom.xml','src'],source,10)
    execute(['git','-c','user.name=RegressLab local evaluation','-c','user.email=evaluation@localhost',
             'commit','-m','Controlled common baseline (not GitHub)'],source,10)
    baseline_commit=execute(['git','rev-parse','HEAD'],source,10)
    execute(['mvn','-B','-ntp','clean','package'],source,180,out/'build-baseline.log')
    shutil.copy(source/'target/benchmarks.jar',out/'baseline.jar')
    candidate_commits={}
    for case in scenarios:
        execute(['git','checkout','--detach',baseline_commit],source,10)
        shutil.copy(ROOT/'scenarios'/case['id']/'candidate.java',dest/(case['className']+'.java'))
        execute(['git','add',case['filePath']],source,10)
        execute(['git','-c','user.name=RegressLab local evaluation','-c','user.email=evaluation@localhost',
                 'commit','-m',f'Independent controlled candidate {case["id"]} (not GitHub)'],source,10)
        sha=execute(['git','rev-parse','HEAD'],source,10)
        candidate_commits[case['id']]=sha
        execute(['git','branch',f'evaluation/{case["id"]}',sha],source,10)
        changed=execute(['git','diff','--name-only',baseline_commit,sha],source,10)
        if changed!=case['filePath']: raise ValueError('Candidate must change exactly its own scenario source')
        execute(['mvn','-B','-ntp','clean','package'],source,180,out/f'build-candidate-{case["id"]}.log')
        shutil.copy(source/'target/benchmarks.jar',out/f'candidate-{case["id"]}.jar')
    environment = dict(origin='LOCAL_EXECUTED_EVALUATION_NOT_GITHUB', os=platform.platform(), cpu=platform.processor(),
                       architecture=platform.machine(), logicalCpuCount=os.cpu_count(), python=platform.python_version(),
                       java=execute(['java','-version'], REPO, 10), startedAt=datetime.now(timezone.utc).isoformat())
    # java -version writes to stderr; capture its complete output separately, without environment credentials.
    jv = subprocess.run(['java','-version'], capture_output=True, check=True)
    environment['java'] = (jv.stdout+jv.stderr).decode().strip()
    environment['database'] = 'Real PostgreSQL via JDBC; per-fork temporary 64-row table, loopback only'
    measurements = dict(runId=str(uuid.uuid4()), protocolVersion='independent-candidates-v2', baselineCommit=baseline_commit, candidateCommits=candidate_commits,
                        environment=environment, configuration=CONFIG, scenarios=[])
    for case in scenarios:
        case_dir = out/case['id']; case_dir.mkdir()
        patch = execute(['git','diff','--no-ext-diff','--unified=3', baseline_commit, candidate_commits[case['id']], '--', case['filePath']], source, 10)
        patch = patch[patch.index('@@'):]
        observed = dict(id=case['id'], candidateCommit=candidate_commits[case['id']], filePath=case['filePath'], patch=patch, baseline=[], candidate=[], executionLedger=[])
        for fork in range(3):
            arms = ('baseline','candidate') if fork % 2 == 0 else ('candidate','baseline')
            for arm in arms:
                # Noise is real CPU work, not synthesized timing values. Explicit confounder, not a source regression.
                count = min(os.cpu_count() or 1, 16) if case['id']=='noisy-control' and (fork==1 or fork==2 and arm=='baseline') else 0
                workers=[]; output=case_dir/f'{arm}-fork-{fork}.jmh.json'
                started=datetime.now(timezone.utc).isoformat(); clock=time.monotonic()
                try:
                    for _ in range(count):
                        workers.append(subprocess.Popen([sys.executable, '-c', 'x=1\nwhile True: x=(x*1664525+1013904223)&0xffffffff'],
                                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
                    execute(['java','-jar',str(out/('baseline.jar' if arm=='baseline' else f'candidate-{case["id"]}.jar')),'EvaluationBenchmarks.operation','-p',f'scenario={case["id"]}',
                             '-f','1','-wi','3','-i','5','-w','500ms','-r','500ms','-jvmArgs','-Xms128m -Xmx128m',
                             '-rf','json','-rff',str(output),'-foe','true'], REPO, 90, case_dir/f'{arm}-fork-{fork}.log')
                    observed[arm].append(read_jmh(output,case['id']))
                finally: stop_workers(workers)
                observed['executionLedger'].append(dict(arm=arm,forkIndex=fork,startedAt=started,elapsedSeconds=time.monotonic()-clock,
                                                         cpuContenders=count,rawArtifactSha256=digest(output)))
                print(f'{case["id"]} {arm} fork {fork+1}: actual JMH observations stored', flush=True)
        measurements['scenarios'].append(observed)
        (out/'partial-measurements.json').write_text(json.dumps(measurements,indent=2)+'\n')
    measurements['completedAt']=datetime.now(timezone.utc).isoformat()
    (out/'measurements.json').write_text(json.dumps(measurements,indent=2)+'\n')
    (out/'ground-truth.json').write_text(json.dumps(scenarios,indent=2)+'\n')
    (out/'reproducibility.json').write_text(json.dumps(dict(harnessSha256=digest(ROOT/'benchmarks/src/main/java/dev/regresslab/evaluation/EvaluationBenchmarks.java'),
        runnerSha256=digest(Path(__file__)), baselineJarSha256=digest(out/'baseline.jar'), candidateJarSha256={case['id']:digest(out/f'candidate-{case["id"]}.jar') for case in scenarios},
        sourceDigests={str(p.relative_to(ROOT)):digest(p) for p in (ROOT/'scenarios').rglob('*.java')},
        ollamaVersion=execute(['curl','--fail','--silent','--max-time','10',ollama_origin()+'/api/version'],REPO,15)),indent=2)+'\n')
    infer(out)

def audit_measurements(out):
    data=json.loads((out/'measurements.json').read_text())
    expected={s['id']:s for s in json.loads((ROOT/'scenarios.json').read_text())}
    if len(data['scenarios'])!=len(expected) or {s['id'] for s in data['scenarios']}!=set(expected) or data['configuration']!=CONFIG:
        raise ValueError('Executed scenario/configuration set mismatch')
    for case in data['scenarios']:
        if case['filePath']!=expected[case['id']]['filePath']: raise ValueError('Changed source path mismatch')
        ledger=case['executionLedger']
        if len(ledger)!=6 or {(e['arm'],e['forkIndex']) for e in ledger}!={(a,f) for a in ('baseline','candidate') for f in range(3)}:
            raise ValueError('Execution ledger mismatch')
        for item in ledger:
            path=out/case['id']/f'{item["arm"]}-fork-{item["forkIndex"]}.jmh.json'
            if digest(path)!=item['rawArtifactSha256'] or read_jmh(path,case['id'])!=case[item['arm']][item['forkIndex']]:
                raise ValueError('Raw JMH hash/observations differ from stored measurements')
    return True

def infer(out):
    if any((out/name).exists() for name in ('model-configuration.json','attempts.json','inference.log')) or any(out.glob('*/input.json')):
        raise FileExistsError('Inference already started here; use a fresh run and preserve all attempts/logs')
    audit_measurements(out)
    execute(['./gradlew','evaluateInvestigator',f'-PevaluationDir={out}'], REPO/'backend', 1500, out/'inference.log')
    report = summarize(out)
    print(json.dumps(report['totals'],indent=2))
    print(f'Full evaluation: {out}/summary.md')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, help='Fresh directory, never overwrite an earlier experiment')
    parser.add_argument('--infer-existing', type=Path, help='Continue an executed measurement run with no existing attempt files')
    parser.add_argument('--score', type=Path, help='Re-score retained attempts without running benchmarks or model')
    args=parser.parse_args()
    if args.score:
        root=args.score.resolve(); audit_measurements(root); report=summarize(root)
        print(json.dumps(report['totals'],indent=2)); print(f'Summary: {root}/summary.md'); return
    if args.infer_existing: infer(args.infer_existing.resolve()); return
    url=os.environ.get('REGRESSLAB_EVAL_JDBC_URL','')
    import re
    if not re.fullmatch(r'jdbc:postgresql://(?:127\.0\.0\.1|localhost):[0-9]{1,5}/[a-zA-Z0-9_]+',url):
        parser.error('REGRESSLAB_EVAL_JDBC_URL must be an explicit loopback PostgreSQL JDBC URL; use an isolated evaluation DB')
    out=(args.output or ROOT/'results'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')).resolve()
    ollama_origin()
    out.mkdir(parents=True, exist_ok=False)  # Allocate ownership outside failure handling; never mutate an earlier run.
    try: run(out)
    except Exception as error:
        if out.exists(): (out/'failure.json').write_text(json.dumps(dict(error=str(error), type=type(error).__name__, completed=False),indent=2)+'\n')
        raise
if __name__=='__main__': main()
