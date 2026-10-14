package dev.regresslab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.Page;
import dev.regresslab.ingestion.Digests;
import dev.regresslab.results.ResultsService;
import jakarta.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiInvestigationService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AiInvestigationService.class);
    private final AiSettings settings;
    private final AiEvidenceService evidence;
    private final PrDiffService diffs;
    private final OllamaClient ollama;
    private final AiReportRepository reports;
    private final AiJobRepository jobs;
    private final ResultsService results;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("ai-investigation-worker").factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService recovery=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ai-job-recovery").factory());
    public AiInvestigationService(AiSettings settings,AiEvidenceService evidence,PrDiffService diffs,OllamaClient ollama,
                                  AiReportRepository reports,AiJobRepository jobs,ResultsService results,ObjectMapper mapper) {
        this.settings=settings;this.evidence=evidence;this.diffs=diffs;this.ollama=ollama;this.reports=reports;this.jobs=jobs;this.results=results;this.mapper=mapper;
    }
    @PostConstruct void startRecovery() {
        recovery.scheduleWithFixedDelay(() -> {
            try { jobs.withWorkerLock(jobs::recoverInterrupted); }
            catch(RuntimeException e) { LOG.warn("AI job recovery unavailable: {}",e.getClass().getSimpleName()); }
        },10,10,TimeUnit.SECONDS);
    }
    private AiEvidenceService.Snapshot authorized(UUID comparison,UUID analysis) {
        if(!settings.enabled()) throw ApiProblem.notFound("AI investigation is disabled.");
        var snapshot=evidence.snapshot(comparison,analysis);
        Double effect=snapshot.evidence().analysis().result().relativeChangePercent();
        if(effect==null || !Double.isFinite(effect) || Math.abs(effect)<snapshot.evidence().analysis().policy().practicalThresholdPercent())
            throw new ApiProblem(HttpStatus.CONFLICT,"NO_PRACTICAL_EFFECT","The observed effect does not reach the stored threshold. No hypotheses were requested; this does not establish equivalence.");
        return snapshot;
    }
    private String requestHash(AiEvidenceService.Snapshot snapshot) {
        return Digests.canonical(mapper,Map.of("snapshot",snapshot,"configuration",ollama.configuration(),"version",OllamaClient.VERSION));
    }
    public JobDto submit(UUID comparison,Request request) {
        // Short authorized DB snapshot only. GitHub and Ollama are contacted by the worker after HTTP acceptance.
        var snapshot=authorized(comparison,request.analysisId());
        var queued=jobs.enqueue(comparison,request.analysisId(),requestHash(snapshot));
        if(queued.created()) {
            try { worker.execute(() -> execute(queued.job().id())); }
            catch(RejectedExecutionException full) { jobs.fail(queued.job().id(),"AI_BUSY","Worker capacity unavailable; no model call was started."); return jobs.get(queued.job().id()); }
        }
        return queued.job();
    }
    void execute(UUID id) {
        try {
            boolean locked=jobs.withWorkerLock(() -> {
                var job=jobs.get(id); if(job.status()!=JobStatus.QUEUED) return;
                jobs.running(id);
                try {
                    var snapshot=authorized(job.comparisonId(),job.analysisId());
                    if(!requestHash(snapshot).equals(jobs.requestHash(id)))
                        throw ApiProblem.conflict("Queued evidence/configuration changed; submit a new job for the current snapshot.");
                    var diff=diffs.fetch(snapshot.repository(),snapshot.prNumber(),snapshot.baselineSha(),snapshot.candidateSha());
                    var original=snapshot.evidence();
                    var conditions=(com.fasterxml.jackson.databind.node.ObjectNode)original.conditions().deepCopy();
                    conditions.set("inferenceConfiguration",mapper.valueToTree(ollama.configuration()));
                    var input=new Input(diff,new Evidence(original.analysis(),original.baselineWorkflowUrl(),original.candidateWorkflowUrl(),original.baselineSamples(),original.candidateSamples(),
                            original.baselineEnvironmentSha256(),original.candidateEnvironmentSha256(),conditions));
                    String hash=Digests.canonical(mapper,Map.of("input",input,"system",OllamaClient.SYSTEM,"version",OllamaClient.VERSION,"configuration",ollama.configuration()));
                    var model=ollama.identify();
                    var cached=reports.reusable(hash,model);
                    if(cached.isPresent()) {
                        if(!cached.get().input().equals(input)) throw new ApiProblem(HttpStatus.BAD_GATEWAY,"INVALID_CACHED_REPORT","Stored report does not match the complete current evidence snapshot.");
                        ReportValidator.validate(cached.get().report(),input);
                        if(!model.equals(ollama.identify())) throw new ApiProblem(HttpStatus.BAD_GATEWAY,"MODEL_CHANGED","Model digest changed while checking report reuse.");
                        jobs.complete(id,cached::get,true);
                    } else {
                        var completion=ollama.generate(input);
                        if(!model.equals(completion.model())) throw new ApiProblem(HttpStatus.BAD_GATEWAY,"MODEL_CHANGED","Model digest changed before generation.");
                        ReportValidator.validate(completion.report(),input);
                        jobs.complete(id,() -> reports.insert(job.comparisonId(),hash,input,completion),false);
                    }
                } catch(ApiProblem p) { jobs.fail(id,p.code(),p.getMessage()); }
                catch(RuntimeException e) { LOG.warn("AI job failed: {}",e.getClass().getSimpleName()); jobs.fail(id,"AI_FAILED","Investigation failed. Existing reports are unchanged."); }
            });
            if(!locked) jobs.fail(id,"AI_BUSY","Another worker owns the database inference lock. No model call was started here.");
        } catch(RuntimeException e) {
            LOG.warn("AI worker infrastructure unavailable: {}",e.getClass().getSimpleName());
            // Recovery records interrupted jobs once the DB is available again; never retry generation automatically.
        }
    }
    public ReportDto get(UUID id) { return reports.get(id); }
    public Page<ReportDto> list(UUID comparison,int page,int size) { results.comparison(comparison); return reports.list(comparison,page,size); }
    public JobDto job(UUID id) { return jobs.get(id); }
    public Page<JobDto> jobs(UUID comparison,int page,int size) { results.comparison(comparison); return jobs.list(comparison,page,size); }
    @PreDestroy public void close() { recovery.shutdownNow();worker.shutdownNow(); }
}
