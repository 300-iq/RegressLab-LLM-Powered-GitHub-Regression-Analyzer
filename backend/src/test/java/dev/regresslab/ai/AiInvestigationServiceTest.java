package dev.regresslab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.results.ResultsService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiInvestigationServiceTest {
    final AiEvidenceService evidence=mock(AiEvidenceService.class);
    final PrDiffService diffs=mock(PrDiffService.class);
    final OllamaClient ollama=mock(OllamaClient.class);
    final AiReportRepository reports=mock(AiReportRepository.class);
    final AiJobRepository jobs=mock(AiJobRepository.class);
    final ResultsService results=mock(ResultsService.class);
    final ObjectMapper mapper=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    final AiInvestigationService service=new AiInvestigationService(new AiSettings(true,"http://127.0.0.1:11434","test:model",120),evidence,diffs,ollama,reports,jobs,results,mapper);
    final UUID id=UUID.randomUUID();
    final Input input=AiTestData.input();
    final ModelIdentity model=new ModelIdentity("test:model","c".repeat(64));
    final JobDto job=new JobDto(id,AiTestData.COMPARISON,input.evidence().analysis().id(),JobStatus.QUEUED,null,false,null,null,Instant.EPOCH,Instant.EPOCH);
    @BeforeEach void setup() {
        var snapshot=new AiEvidenceService.Snapshot("fixture/demo",1,"a".repeat(40),"b".repeat(40),input.evidence());
        when(evidence.snapshot(any(),any())).thenReturn(snapshot);
        when(diffs.fetch(anyString(),anyInt(),anyString(),anyString())).thenReturn(input.diff());
        when(ollama.configuration()).thenReturn(Map.of("model","test:model"));
        when(ollama.identify()).thenReturn(model);
        when(jobs.get(id)).thenReturn(job);
        when(jobs.requestHash(id)).thenReturn(dev.regresslab.ingestion.Digests.canonical(mapper,Map.of("snapshot",snapshot,"configuration",Map.of("model","test:model"),"version",OllamaClient.VERSION)));
        when(jobs.withWorkerLock(any())).thenAnswer(call -> { call.<Runnable>getArgument(0).run();return true; });
        when(reports.reusable(anyString(),any())).thenReturn(Optional.empty());
        when(jobs.complete(any(),any(),anyBoolean())).thenAnswer(call -> { call.<java.util.function.Supplier<ReportDto>>getArgument(1).get();return job; });
    }
    @AfterEach void close() { service.close(); }
    @Test void repeatedSubmissionReturnsActiveJobWithoutHttpInference() {
        when(jobs.enqueue(any(),any(),anyString())).thenReturn(new AiJobRepository.Enqueued(job,false));
        assertThat(service.submit(job.comparisonId(),new Request(job.analysisId()))).isEqualTo(job);
        verifyNoInteractions(diffs,reports);verify(ollama,never()).generate(any());
    }
    @Test void modelFailureRecordsFailedJobAndNeverStoresReport() {
        when(ollama.generate(any())).thenThrow(new ApiProblem(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,"OLLAMA_TIMEOUT","Timeout"));
        service.execute(id);
        verify(jobs).fail(id,"OLLAMA_TIMEOUT","Timeout");verify(reports,never()).insert(any(),anyString(),any(),any());
    }
    @Test void invalidReferencesNeverReachPersistence() {
        var h=AiTestData.report().hypotheses().getFirst();
        var invalid=new ModelReport(List.of(new Hypothesis(h.potentialCause(),List.of(new Reference("invented.java",Side.CANDIDATE,1)),h.comparisonId(),h.technicalExplanation(),h.verificationExperiment(),h.uncertainty())),List.of("Uncertain"));
        when(ollama.generate(any())).thenReturn(new Completion(model,invalid,"{}")); service.execute(id);
        verify(reports,never()).insert(any(),anyString(),any(),any());verify(jobs).fail(eq(id),eq("INVALID_AI_RESPONSE"),anyString());
    }
    @Test void storesNewReportOnlyAfterGenerationAndValidation() {
        var completion=new Completion(model,AiTestData.report(),"{}");when(ollama.generate(any())).thenReturn(completion);
        service.execute(id);
        var order=inOrder(ollama,jobs,reports);order.verify(ollama).generate(any());order.verify(jobs).complete(eq(id),any(),eq(false));
        order.verify(reports).insert(eq(AiTestData.COMPARISON),matches("[0-9a-f]{64}"),any(),eq(completion));
    }
    Input cachedInput() {
        var e=input.evidence();var c=mapper.createObjectNode();c.set("inferenceConfiguration",mapper.valueToTree(Map.of("model","test:model")));
        return new Input(input.diff(),new Evidence(e.analysis(),e.baselineWorkflowUrl(),e.candidateWorkflowUrl(),e.baselineSamples(),e.candidateSamples(),e.baselineEnvironmentSha256(),e.candidateEnvironmentSha256(),c));
    }
    @Test void matchingEvidenceAndModelReusesReportWithoutInference() {
        var stored=new ReportDto(UUID.randomUUID(),job.comparisonId(),OllamaClient.VERSION,"a".repeat(64),model.name(),model.digest(),"UNVERIFIED_HYPOTHESES",cachedInput(),AiTestData.report(),List.of(),Instant.EPOCH);
        when(reports.reusable(anyString(),eq(model))).thenReturn(Optional.of(stored));service.execute(id);
        verify(ollama,never()).generate(any());verify(reports,never()).insert(any(),anyString(),any(),any());verify(jobs).complete(eq(id),any(),eq(true));
    }
    @Test void modelDigestChangedDuringReuseFailsWithoutChangingReport() {
        var stored=new ReportDto(UUID.randomUUID(),job.comparisonId(),OllamaClient.VERSION,"a".repeat(64),model.name(),model.digest(),"UNVERIFIED_HYPOTHESES",cachedInput(),AiTestData.report(),List.of(),Instant.EPOCH);
        when(reports.reusable(anyString(),eq(model))).thenReturn(Optional.of(stored));when(ollama.identify()).thenReturn(model,new ModelIdentity(model.name(),"d".repeat(64)));
        service.execute(id);verify(jobs).fail(eq(id),eq("MODEL_CHANGED"),anyString());verify(jobs,never()).complete(any(),any(),anyBoolean());
    }
    @Test void staleQueuedEvidenceOrMissingWorkerLockNeverSendsContent() {
        when(jobs.requestHash(id)).thenReturn("different");service.execute(id);
        verifyNoInteractions(diffs);verify(ollama,never()).generate(any());
        doReturn(false).when(jobs).withWorkerLock(any());service.execute(id);verify(jobs).fail(eq(id),eq("AI_BUSY"),anyString());
    }
    @Test void belowThresholdObservationDoesNotCreateJob() {
        var i=AiTestData.input(.1);when(evidence.snapshot(any(),any())).thenReturn(new AiEvidenceService.Snapshot("fixture/demo",1,"a".repeat(40),"b".repeat(40),i.evidence()));
        assertThatThrownBy(() -> service.submit(AiTestData.COMPARISON,new Request(UUID.randomUUID()))).isInstanceOf(ApiProblem.class);
        verify(jobs,never()).enqueue(any(),any(),anyString());verifyNoInteractions(diffs,reports);
    }
    @Test void offlineMetadataDoesNotChangeSavedReportsOrRequestGeneration() {
        when(ollama.identify()).thenThrow(new ApiProblem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"OLLAMA_UNAVAILABLE","Offline"));
        service.execute(id);verify(jobs).fail(id,"OLLAMA_UNAVAILABLE","Offline");verifyNoInteractions(reports);verify(ollama,never()).generate(any());
    }
}
