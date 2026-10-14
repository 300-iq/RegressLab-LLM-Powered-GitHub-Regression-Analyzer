"""Auditable rubric v2: lexical proxies, not an assertion of semantic correctness or causal truth."""
import json
from pathlib import Path
import re

VERSION='controlled-investigator-rubric-v2'

def strict_json(text):
    def pairs(items):
        result={}
        for key,value in items:
            if key in result: raise ValueError('duplicate JSON key')
            result[key]=value
        return result
    return json.loads(text, object_pairs_hook=pairs, parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))

def score_report(report, input_data, truth, accepted):
    hypotheses=report.get('hypotheses',[]) if isinstance(report,dict) else []
    if not isinstance(hypotheses,list): raise ValueError('hypotheses must be a list')
    allowed={(f['filePath'],l['side'],l['line']) for f in input_data['diff']['files'] for l in f['changedLines']}
    code={(f['filePath'],l['side'],l['line']):l['code'] for f in input_data['diff']['files'] for l in f['changedLines']}
    result=dict(hypothesisCount=len(hypotheses), referencesAssessed=0, unsupportedReferences=0, malformedReferences=0, mechanismKeywordMatch=False,
                controlAbstained=None, experimentsPassingRubric=0, uncertaintyAcknowledged=False, concerns=[])
    texts=[]
    for h in hypotheses:
        if not isinstance(h,dict): raise ValueError('hypothesis must be an object')
        refs=h.get('references',[])
        if not isinstance(refs,list): raise ValueError('references must be a list')
        result['referencesAssessed']+=len(refs)
        valid=True; executable_reference=False
        for ref in refs:
            if not isinstance(ref,dict) or type(ref.get('line')) is not int or not isinstance(ref.get('filePath'),str) or not isinstance(ref.get('side'),str):
                result['malformedReferences']+=1; valid=False; continue
            key=(ref['filePath'],ref['side'],ref['line'])
            if key not in allowed: result['unsupportedReferences']+=1; valid=False
            elif ref['side']=='CANDIDATE' and not code[key].strip().startswith('//'):
                executable_reference=True
        explanation=str(h.get('potentialCause',''))+' '+str(h.get('technicalExplanation',''))
        mechanism=all(any(word in explanation.lower() for word in group) for group in truth['mechanismKeywordGroups'])
        if truth['positive'] and accepted and valid and refs and executable_reference and mechanism:
            result['mechanismKeywordMatch']=True
        experiment=str(h.get('verificationExperiment','')).lower()
        unsound=re.search(r'single.{0,80}(iteration|run|sample|invocation|call)|(?:skip|avoid).{0,30}warmup',experiment)
        targeted=any(x in experiment for x in ('profil','allocat','query count','queries','gc','scal','parsing','parser','revert'))
        repeated=bool(re.search(r'(multiple|repeat|independent).{0,45}fork|fork.{0,45}(multiple|repeat|independent)',experiment))
        matched=any(x in experiment for x in ('matched','same conditions','identical conditions','same environment','controlled conditions'))
        passed=accepted and targeted and repeated and matched and 'warmup' in experiment and not unsound
        if passed: result['experimentsPassingRubric']+=1
        else: result['concerns'].append('Experiment lacks one or more explicit targeted measurement, matched conditions, warmup, independent repeated forks; human review required.')
        texts.append(str(h.get('uncertainty','')))
    texts+=report.get('missingEvidence',[]) if isinstance(report.get('missingEvidence'),list) else []
    uncertainty=' '.join(str(t) for t in texts).lower()
    result['uncertaintyAcknowledged']=accepted and bool(re.search(r'fork|inconclusive|insufficient|noise|contention|uncertain|profil|cannot|not (?:proven|verified)|missing|lack',uncertainty))
    if not truth['positive']:
        result['controlAbstained']=accepted and not hypotheses
        if hypotheses: result['concerns'].append('Hypotheses on a control without an injected code regression; citations alone do not establish relevance.')
    return result

def summarize(root):
    root=Path(root)
    truths={s['id']:s for s in strict_json((root/'ground-truth.json').read_text())}
    attempts=strict_json((root/'attempts.json').read_text())
    rows=[]
    for attempt in attempts:
        sid=attempt['id']; directory=root/sid
        input_data=strict_json((directory/'input.json').read_text())
        row=dict(id=sid,status=attempt['status'],elapsedSeconds=attempt['elapsedSeconds'],
                 knownInjectedIssue=truths[sid]['knownIssue'],classification=input_data['evidence']['analysis']['result']['classification'],
                 observedRelativeChangePercent=input_data['evidence']['analysis']['result']['relativeChangePercent'],
                 baselineForkCv=input_data['evidence']['analysis']['result']['baseline']['forkMeans']['coefficientOfVariation'],
                 candidateForkCv=input_data['evidence']['analysis']['result']['candidate']['forkMeans']['coefficientOfVariation'],
                 productionThresholdGuardWouldDecline=attempt['productionThresholdGuardWouldDecline'], citationsAssessed=False)
        report=None
        try:
            envelope=strict_json((directory/'raw-response.json').read_text())
            report=strict_json(envelope['message']['content'])
            row.update(score_report(report,input_data,truths[sid],attempt['status']=='ACCEPTED_UNVERIFIED_HYPOTHESES'))
            row['citationsAssessed']=True
            row['ollamaEvalDurationSeconds']=envelope.get('eval_duration',0)/1e9
            row['ollamaTotalDurationSeconds']=envelope.get('total_duration',0)/1e9
        except (OSError,ValueError,KeyError,TypeError) as error:
            row.update(mechanismKeywordMatch=False,referencesAssessed=None,unsupportedReferences=None,malformedReferences=None,hypothesisCount=None,
                       controlAbstained=False,experimentsPassingRubric=0,uncertaintyAcknowledged=False,
                       concerns=[f'Raw content not assessable: {type(error).__name__}; do not count missing citations as zero hallucinations.'])
        rows.append(row)
    totals=dict(scenarios=len(rows),positiveScenarios=sum(t['positive'] for t in truths.values()),
                acceptedReports=sum(r['status']=='ACCEPTED_UNVERIFIED_HYPOTHESES' for r in rows),
                rejectedReports=sum(r['status']!='ACCEPTED_UNVERIFIED_HYPOTHESES' for r in rows),
                scenariosWithMechanismKeywordMatch=sum(r['mechanismKeywordMatch'] for r in rows),
                unsupportedReferences=sum(r['unsupportedReferences'] or 0 for r in rows),
                malformedReferences=sum(r['malformedReferences'] or 0 for r in rows),
                scenariosWithUnassessableCitations=sum(not r['citationsAssessed'] for r in rows),
                hypothesesAssessed=sum(r['hypothesisCount'] or 0 for r in rows),
                referencesAssessed=sum(r['referencesAssessed'] or 0 for r in rows),
                experimentsPassingRubric=sum(r['experimentsPassingRubric'] for r in rows),
                scenariosAcknowledgingUncertainty=sum(r['uncertaintyAcknowledged'] for r in rows),
                controlsAbstaining=sum(r['controlAbstained'] is True for r in rows))
    adjudication=None
    if (root/'adjudication.json').exists():
        import hashlib
        adjudication=strict_json((root/'adjudication.json').read_text())
        by_id={r['id']:r for r in adjudication['scenarios']}
        if set(by_id)!=set(truths) or len(by_id)!=len(adjudication['scenarios']):
            raise ValueError('Review must cover exactly the executed scenarios')
        for row in rows:
            review=by_id[row['id']]
            for filename,field in [('raw-response.json','rawResponseSha256'),('input.json','inputSha256')]:
                if hashlib.sha256((root/row['id']/filename).read_bytes()).hexdigest()!=review[field]:
                    raise ValueError('Adjudication digest does not match retained evidence')
            row['review']=review
        totals['reviewedScenarios']=len(rows)
        totals['reviewedScenariosWithUsefulHypothesis']=sum(r['review']['usefulHypothesis'] for r in rows)
        totals['reviewedValidVerificationExperiments']=sum(r['review']['validVerificationExperiment'] for r in rows)
        totals['reviewedAppropriateUncertainty']=sum(r['review'].get('uncertaintyAppropriate',False) for r in rows)
    else:
        totals['reviewedScenarios']=0
        totals['reviewedScenariosWithUsefulHypothesis']=None
        totals['reviewedValidVerificationExperiments']=None
        totals['reviewedAppropriateUncertainty']=None
    report=dict(rubricVersion=VERSION,totals=totals,scenarios=rows,adjudication=adjudication,
                limitations=['One attempt per scenario, one installed model; no model-quality confidence interval.',
                             'Lexical rubric is a transparent triage proxy; human semantic review required.',
                             'Three JVM forks per arm; all classifications inconclusive. Exploratory intervals do not prove causal effects.',
                             'Local controlled workload; no production/GitHub measurement attestation. Controls do not prove equivalence.',
                             'Experiments are assessed as suggestions, never executed. No commands or tools provided to the model.'])
    (root/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    lines=['# Executed local investigator evaluation','',f'Rubric: `{VERSION}`. All model conclusions are unverified hypotheses.','',
           '| Scenario | Observed change | Fork CV baseline / candidate | Output | Mechanism keywords (proxy) | Experiments passing | Uncertainty proxy | Inference wall seconds |',
           '| --- | ---: | --- | --- | --- | ---: | --- | ---: |']
    for r in rows:
        lines.append(f'| {r["id"]} | {r["observedRelativeChangePercent"]:.4f}% | {r["baselineForkCv"]:.4f} / {r["candidateForkCv"]:.4f} | {r["status"]} | {r["mechanismKeywordMatch"]} | {r["experimentsPassingRubric"]} | {r["uncertaintyAcknowledged"]} | {r["elapsedSeconds"]:.3f} |')
    lines+=['','```json',json.dumps(totals,indent=2),'```','']
    lines+=['- '+lim for lim in report['limitations']]
    lines+=['','## Per-scenario concerns','']
    for r in rows:
        lines += [f'### {r["id"]}', '', 'Injected change: '+r['knownInjectedIssue'],'',
                  'Production effect guard would decline: '+str(r['productionThresholdGuardWouldDecline']), '']
        lines += ['- '+c for c in r['concerns']] or ['- No lexical rubric concern; this is not semantic validation.']
        if 'review' in r: lines+=['','Review notes: '+r['review']['notes']]
        lines.append('')
    (root/'summary.md').write_text('\n'.join(lines)+'\n')
    return report
