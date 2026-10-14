package dev.regresslab;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.regresslab.github.*;
import dev.regresslab.github.GithubDtos.*;
import dev.regresslab.ingestion.Digests;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Actual archived measurements; a simulated metadata authority. Isolated test DB,
// not live GitHub verification or production API fallback.
@ActiveProfiles("local-ingestion")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GithubImportIntegrationTest {
    static final String TOKEN="test-only-github-import-token-01234567890123456789";
    static final long RUN=38449649490L, BASE=11838908962L, CANDIDATE=11839538807L;
    static final EmbeddedPostgres PG=start();
    static EmbeddedPostgres start() { try{return EmbeddedPostgres.builder().setPort(0).start();}catch(IOException e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");
        registry.add("regresslab.local-ingestion.token",()->TOKEN);registry.add("regresslab.local-ingestion.repository-github-id",()->1417739588L);
        registry.add("regresslab.local-ingestion.repository-full-name",()->"300-iq/regresslab-java-demo");registry.add("regresslab.local-ingestion.requests-per-minute",()->1000);
        registry.add("regresslab.github-import.enabled",()->true);
    }
    static dev.regresslab.ai.AiInvestigationService backgroundAi;
    @Autowired void captureAi(dev.regresslab.ai.AiInvestigationService service) { backgroundAi=service; }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired GithubPipeline pipeline;
    @Autowired GithubArchiveParser parser;
    @MockitoBean GithubApi api;
    Map<String,JsonNode> metadata;
    @BeforeEach void reset() throws Exception {
        jdbc.execute("TRUNCATE repositories CASCADE");
        metadata=metadata();
        when(api.get(anyString())).thenAnswer(invocation->{var path=invocation.getArgument(0,String.class);if(!metadata.containsKey(path))throw new AssertionError("Unexpected API path: "+path);return metadata.get(path);});
    }
    @AfterAll static void stop() throws IOException { if(backgroundAi!=null)backgroundAi.close(); PG.close(); }
    ObjectNode node(Object value) { return mapper.valueToTree(value); }
    String prefix() {return "/repos/300-iq/regresslab-java-demo";}
    Map<String,JsonNode> metadata() throws Exception {
        var baseline=parser.parse(GithubArchiveParserTest.evidence("baseline"));var candidate=parser.parse(GithubArchiveParserTest.evidence("candidate"));
        var pr=baseline.artifact().pullRequest();
        var repo=node(Map.of("id",1417739588L,"full_name","300-iq/regresslab-java-demo","private",false));
        var base=node(Map.of("sha",pr.baselineSha(),"ref","main","repo",repo));
        var head=node(Map.of("sha",pr.candidateSha(),"ref","demo/aggregate-on-read","repo",repo));
        var attached=node(Map.of("number",1,"base",base,"head",head));
        var run=node(Map.of("id",RUN,"run_attempt",1,"event","pull_request","status","completed","repository",repo,"workflow_id",383549664L,"head_sha",pr.candidateSha(),"pull_requests",List.of(attached),"created_at","2026-10-13T18:47:46Z","updated_at","2026-10-13T18:50:02Z"));
        var pull=node(Map.of("number",1,"title",pr.title(),"state","open","base",base,"head",head));
        var workflow=node(Map.of("id",383549664L,"path",".github/workflows/benchmark.yml"));
        var tree=node(Map.of("truncated",false,"tree",pipeline.configuration().files().entrySet().stream().map(e->Map.of("path",e.getKey(),"sha",e.getValue(),"type","blob","mode","100644")).toList()));
        var result=new HashMap<String,JsonNode>();
        result.put(prefix(),repo);result.put(prefix()+"/actions/runs/"+RUN,run);result.put(prefix()+"/pulls/1",pull);result.put(prefix()+"/actions/workflows/benchmark.yml",workflow);
        for(var entry:List.of(Map.entry(BASE,baseline),Map.entry(CANDIDATE,candidate))) {
            var a=entry.getValue().artifact();
            var meta=node(Map.of("id",entry.getKey(),"name","regresslab-"+(entry.getKey()==BASE?"baseline":"candidate")+"-1","expired",false,"size_in_bytes",entry.getValue().archiveBytes().length,"digest","sha256:"+Digests.bytes(entry.getValue().archiveBytes()),"workflow_run",Map.of("id",RUN,"repository_id",1417739588L,"head_repository_id",1417739588L,"head_sha",pr.candidateSha())));
            result.put(prefix()+"/actions/artifacts/"+entry.getKey(),meta);
            String sha=a.run().commitSha(), treeSha=(entry.getKey()==BASE?"c":"d").repeat(40);
            result.put(prefix()+"/git/commits/"+sha,node(Map.of("sha",sha,"tree",Map.of("sha",treeSha))));
            result.put(prefix()+"/git/trees/"+treeSha+"?recursive=1",tree.deepCopy());
        }
        return result;
    }
    ObjectNode request() {
        return node(Map.of("workflowRunId",RUN,"baselineArtifactId",BASE,"candidateArtifactId",CANDIDATE,
                "baselineArchiveChunks",chunks(GithubArchiveParserTest.evidence("baseline")),"candidateArchiveChunks",chunks(GithubArchiveParserTest.evidence("candidate"))));
    }
    List<String> chunks(byte[] archive) {String encoded=Base64.getEncoder().encodeToString(archive);var list=new ArrayList<String>();for(int i=0;i<encoded.length();i+=1000)list.add(encoded.substring(i,Math.min(i+1000,encoded.length())));return list;}
    ResponseEntity<JsonNode> post(ObjectNode body,boolean authenticated) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(authenticated)headers.setBearerAuth(TOKEN);
        return http.postForEntity("/api/v1/dev/github-imports",new HttpEntity<>(body.toString(),headers),JsonNode.class);
    }
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @Test void checkedProvenanceCreatesANewAnalysisSnapshotWithoutOverwritingTheLocalOne() throws Exception {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(TOKEN);
        JsonNode[] arms=new JsonNode[2];
        for(int i=0;i<2;i++) {
            var archive=parser.parse(GithubArchiveParserTest.evidence(i==0?"baseline":"candidate"));
            var response=http.postForEntity("/api/v1/dev/artifacts",new HttpEntity<>(new String(archive.runBytes(),java.nio.charset.StandardCharsets.UTF_8),headers),JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);arms[i]=response.getBody();
        }
        var detail=http.getForObject("/api/v1/runs/"+arms[0].path("runId").asText(),JsonNode.class);
        var body=node(Map.of("baselineRunId",arms[0].path("runId").asText(),"candidateRunId",arms[1].path("runId").asText(),
                "benchmarkCaseId",detail.path("benchmarks").get(0).path("benchmarkCase").path("id").asText()));
        var comparison=http.postForEntity("/api/v1/comparisons",new HttpEntity<>(body.toString(),headers),JsonNode.class).getBody();
        String path="/api/v1/comparisons/"+comparison.path("id").asText()+"/analysis";
        var local=http.postForEntity(path,new HttpEntity<>("{}",headers),JsonNode.class).getBody();
        assertThat(local.path("provenance").asText()).isEqualTo("LOCAL_UNVERIFIED");
        assertThat(post(request(),true).getStatusCode()).isEqualTo(HttpStatus.OK);
        var checked=http.postForEntity(path,new HttpEntity<>("{}",headers),JsonNode.class).getBody();
        assertThat(checked.path("provenance").asText()).isEqualTo("GITHUB_METADATA");
        assertThat(checked.path("id")).isNotEqualTo(local.path("id"));
        assertThat(checked.path("inputSha256")).isNotEqualTo(local.path("inputSha256"));
        assertThat(http.getForObject("/api/v1/comparison-analyses/"+local.path("id").asText(),JsonNode.class)).isEqualTo(local);
        assertThat(http.postForEntity(path,new HttpEntity<>("{}",headers),JsonNode.class).getBody()).isEqualTo(checked);
        assertThat(count("comparison_analyses")).isEqualTo(2);assertThat(count("benchmark_samples")).isEqualTo(60);
    }
    @Test void atomicallyPersistsBothArmsRawArchivesEvidenceAndMetadataProvenance() {
        var first=post(request(),true);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().path("baseline").path("provenance").asText()).isEqualTo("GITHUB_METADATA");
        assertThat(count("benchmark_runs")).isEqualTo(2);assertThat(count("benchmark_samples")).isEqualTo(60);assertThat(count("github_artifact_imports")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT archive_bytes FROM github_artifact_imports WHERE github_artifact_id=?",byte[].class,BASE)).isEqualTo(GithubArchiveParserTest.evidence("baseline"));
        String run=first.getBody().path("baseline").path("runId").asText(),pr=first.getBody().path("baseline").path("pullRequestId").asText();
        var audit=http.getForEntity("/api/v1/runs/"+run+"/github-audit",JsonNode.class);assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(audit.getBody().path("execution").path("blocks")).hasSize(6);
        assertThat(http.getForObject("/api/v1/pull-requests/"+pr,JsonNode.class).path("pullRequest").path("provenance").asText()).isEqualTo("GITHUB_METADATA");
        var replay=post(request(),true);assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(replay.getBody().path("baseline").path("runId")).isEqualTo(first.getBody().path("baseline").path("runId"));assertThat(count("github_artifact_imports")).isEqualTo(2);
    }
    @Test void anonymousWritesAreRejectedBeforeAnyGithubAccess() {
        var response=post(request(),false);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);verifyNoInteractions(api);assertThat(count("repositories")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"private","digest","expired","event","head","workflow","tree","prChanged","attempt","symlink"})
    void rejectsInvalidPublicAuthorityEvidenceWithoutDatabaseWrites(String field) {
        var run=(ObjectNode)metadata.get(prefix()+"/actions/runs/"+RUN);
        var artifact=(ObjectNode)metadata.get(prefix()+"/actions/artifacts/"+CANDIDATE);
        switch(field) {
            case "private" -> ((ObjectNode)metadata.get(prefix())).put("private",true);
            case "digest" -> artifact.put("digest","sha256:"+"a".repeat(64));
            case "expired" -> artifact.put("expired",true);
            case "event" -> run.put("event","workflow_run");
            case "head" -> run.put("head_sha","0".repeat(40));
            case "workflow" -> ((ObjectNode)metadata.get(prefix()+"/actions/workflows/benchmark.yml")).put("path","unapproved.yml");
            case "symlink" -> ((ObjectNode)metadata.get(prefix()+"/git/trees/"+"d".repeat(40)+"?recursive=1").path("tree").get(0)).put("mode","120000");
            case "tree" -> ((ObjectNode)metadata.get(prefix()+"/git/trees/"+"d".repeat(40)+"?recursive=1").path("tree").get(0)).put("sha","0".repeat(40));
            case "prChanged" -> ((ObjectNode)metadata.get(prefix()+"/pulls/1").path("head")).put("sha","0".repeat(40));
            case "attempt" -> run.put("run_attempt",2);
            default -> throw new AssertionError();
        }
        assertThat(post(request(),true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(count("repositories")).isZero();assertThat(count("github_artifact_imports")).isZero();
    }
    @Test void replayCannotLabelDifferentLocallyUploadedPrMetadataAsGithubChecked() {
        var original=post(request(),true);assertThat(original.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var local=node(parser.parse(GithubArchiveParserTest.evidence("candidate")).artifact());
        local.put("artifactId","test-only-unverified-new-pr-snapshot");
        ((ObjectNode)local.path("pullRequest")).put("title","TEST ONLY: unverified new snapshot").put("candidateSha","c".repeat(40));
        ((ObjectNode)local.path("run")).put("workflowRunId",RUN+1).put("commitSha","c".repeat(40));
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(TOKEN);
        assertThat(http.postForEntity("/api/v1/dev/artifacts",new HttpEntity<>(local.toString(),headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(request(),true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var pr=http.getForObject("/api/v1/pull-requests/"+original.getBody().path("baseline").path("pullRequestId").asText(),JsonNode.class).path("pullRequest");
        assertThat(pr.path("provenance").asText()).isEqualTo("LOCAL_UNVERIFIED");
        assertThat(pr.path("candidateSha").asText()).isEqualTo("c".repeat(40));
        assertThat(count("github_artifact_imports")).isEqualTo(2);
    }
    @Test void legitimateLongGithubPrBodyDoesNotBreakStoredAuditReads() throws Exception {
        String body="Test-only public metadata. ".repeat(300);
        ((ObjectNode)metadata.get(prefix()+"/pulls/1")).put("body",body);
        var imported=post(request(),true);assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var audit=http.getForEntity("/api/v1/runs/"+imported.getBody().path("baseline").path("runId").asText()+"/github-audit",String.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new ObjectMapper().readTree(audit.getBody()).path("verificationEvidence").path("pullRequest").path("body").asText()).isEqualTo(body);
    }
    @Test void githubOutageCannotCreateUnverifiedFallbackRecords() {
        org.mockito.Mockito.doThrow(new dev.regresslab.api.ApiProblem(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_UNAVAILABLE","Test-only upstream outage")).when(api).get(anyString());
        assertThat(post(request(),true).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);assertThat(count("repositories")).isZero();
    }
    @Test void aLateCandidateIdentityConflictRollsBackTheBaselineAndAllProofWrites() {
        var original=parser.parse(GithubArchiveParserTest.evidence("candidate")).artifact();
        var body=node(original);body.put("artifactId","test-only-preexisting-candidate");
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(TOKEN);
        assertThat(http.postForEntity("/api/v1/dev/artifacts",new HttpEntity<>(body.toString(),headers),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(request(),true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("benchmark_runs")).isEqualTo(1);assertThat(count("github_artifact_imports")).isZero();
        assertThat(jdbc.queryForObject("SELECT provenance FROM pull_requests",String.class)).isEqualTo("LOCAL_UNVERIFIED");
    }
    @Test void concurrentReplayRemainsOnePair() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var body=request();var a=pool.submit(()->post(body,true));var b=pool.submit(()->post(body,true));
            assertThat(List.of(a.get().getStatusCode().value(),b.get().getStatusCode().value()).stream().sorted().toList()).containsExactly(200,201);
            assertThat(count("benchmark_runs")).isEqualTo(2);assertThat(count("github_artifact_imports")).isEqualTo(2);
        }
    }
    @Test void comparisonsRetainCheckedMetadataButNeverClaimSignificance() {
        var pair=post(request(),true).getBody();String baseline=pair.path("baseline").path("runId").asText(),candidate=pair.path("candidate").path("runId").asText();
        var run=http.getForObject("/api/v1/runs/"+baseline,JsonNode.class);String caseId=run.path("benchmarks").get(0).path("benchmarkCase").path("id").asText();
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(TOKEN);
        var comparison=http.postForEntity("/api/v1/comparisons",new HttpEntity<>(node(Map.of("baselineRunId",baseline,"candidateRunId",candidate,"benchmarkCaseId",caseId)).toString(),headers),JsonNode.class).getBody();
        assertThat(comparison.path("provenance").asText()).isEqualTo("GITHUB_METADATA");assertThat(comparison.path("analysisStatus").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(comparison.path("reasons").toString()).contains("ARTIFACT_MEASUREMENTS_UNTRUSTED").doesNotContain("LOCAL_PROVENANCE_UNVERIFIED");
    }
}
