package dev.regresslab.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import dev.regresslab.github.GithubDtos.*;
import dev.regresslab.ingestion.Digests;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class GithubVerifier {
    private final GithubApi github;
    private final GithubPipeline reviewed;
    private final ObjectMapper mapper;
    public GithubVerifier(GithubApi github, GithubPipeline reviewed, ObjectMapper mapper) { this.github=github; this.reviewed=reviewed; this.mapper=mapper; }
    public VerifiedPair verify(long runId,long baselineId,long candidateId,Archive baseline,Archive candidate) {
        var pipeline=reviewed.configuration();
        if (pipeline==null) throw ApiProblem.notFound("GitHub artifact imports are disabled.");
        if (baselineId==candidateId) invalid();
        var b=baseline.artifact(); var c=candidate.artifact();
        if (b.run().workflowRunId()!=runId || c.run().workflowRunId()!=runId || b.run().runAttempt()!=c.run().runAttempt()
                || !b.pullRequest().equals(c.pullRequest()) || !b.repository().equals(c.repository())
                || !baseline.execution().equals(candidate.execution()) || !baseline.execution().harnessSha256().equals(pipeline.harnessSha256())
                || b.dataOrigin()!=DataOrigin.REAL || c.dataOrigin()!=DataOrigin.REAL
                || b.repository().githubId()!=pipeline.repositoryGithubId() || !b.repository().fullName().equals(pipeline.repositoryFullName())
                || !b.run().commitSha().equals(b.pullRequest().baselineSha()) || !c.run().commitSha().equals(c.pullRequest().candidateSha())
                || !b.run().environment().equals(c.run().environment())
                || !b.artifactId().equals("github-"+runId+"-"+b.run().runAttempt()+"-baseline")
                || !c.artifactId().equals("github-"+runId+"-"+c.run().runAttempt()+"-candidate")) invalid();
        String prefix="/repos/"+pipeline.repositoryFullName();
        var repo=github.get(prefix);
        if (integer(repo,"id")!=pipeline.repositoryGithubId() || !text(repo,"full_name").equals(pipeline.repositoryFullName())
                || !repo.path("private").isBoolean() || repo.path("private").booleanValue()) invalid();
        var run=github.get(prefix+"/actions/runs/"+runId);
        var workflow=github.get(prefix+"/actions/workflows/benchmark.yml");
        if (integer(run,"id")!=runId || integer(run,"run_attempt")!=b.run().runAttempt()
                || !text(run,"event").equals("pull_request") || !text(run,"status").equals("completed")
                || integer(run.path("repository"),"id")!=pipeline.repositoryGithubId()
                || integer(run,"workflow_id")!=integer(workflow,"id") || !text(workflow,"path").equals(pipeline.workflowPath())
                || !text(run,"head_sha").equals(c.run().commitSha())) invalid();
        var association=new ArrayList<JsonNode>();
        run.path("pull_requests").forEach(pr -> { if (pr.path("number").isIntegralNumber() && pr.path("number").longValue()==b.pullRequest().number()) association.add(pr); });
        if (association.size()!=1) invalid();
        var attached=association.getFirst();
        var pr=github.get(prefix+"/pulls/"+b.pullRequest().number());
        if (integer(pr,"number")!=b.pullRequest().number() || integer(pr.path("base").path("repo"),"id")!=pipeline.repositoryGithubId()
                || !text(pr.path("base"),"sha").equals(b.run().commitSha()) || !text(pr.path("head"),"sha").equals(c.run().commitSha())
                || !text(attached.path("base"),"sha").equals(b.run().commitSha()) || !text(attached.path("head"),"sha").equals(c.run().commitSha())
                || !text(pr,"title").equals(b.pullRequest().title()) || !text(pr,"state").equalsIgnoreCase(b.pullRequest().state().name())
                || !text(pr.path("base"),"ref").equals(b.run().branch()) || !text(pr.path("head"),"ref").equals(c.run().branch())) invalid();
        String headRepo=text(pr.path("head").path("repo"),"full_name");
        if (!headRepo.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")) invalid();
        var baselineTree=tree(pipeline.repositoryFullName(),b.run().commitSha());
        var candidateTree=tree(headRepo,c.run().commitSha());
        for (var entry:pipeline.files().entrySet()) {
            if (!entry.getValue().equals(blob(baselineTree,entry.getKey())) || !entry.getValue().equals(blob(candidateTree,entry.getKey()))) invalid();
        }
        var ba=github.get(prefix+"/actions/artifacts/"+baselineId);
        var ca=github.get(prefix+"/actions/artifacts/"+candidateId);
        verifyArtifact(ba,baselineId,baseline,"baseline",run,pr);
        verifyArtifact(ca,candidateId,candidate,"candidate",run,pr);
        var evidence=mapper.valueToTree(Map.of("repository",repo,"run",run,"workflow",workflow,"pullRequest",pr,
                "baselineTree",baselineTree,"candidateTree",candidateTree,"pipeline",pipeline,"githubApiVersion","2026-03-10"));
        var be=evidence.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)be).set("artifact",ba);
        var ce=evidence.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)ce).set("artifact",ca);
        return new VerifiedPair(new VerifiedArchive(baselineId,baseline,be),new VerifiedArchive(candidateId,candidate,ce));
    }
    private JsonNode tree(String repo,String sha) {
        var commit=github.get("/repos/"+repo+"/git/commits/"+sha);
        if (!text(commit,"sha").equals(sha)) invalid();
        String tree=text(commit.path("tree"),"sha");
        if (!tree.matches("[0-9a-f]{40}")) invalid();
        var result=github.get("/repos/"+repo+"/git/trees/"+tree+"?recursive=1");
        if (!result.path("truncated").isBoolean() || result.path("truncated").booleanValue() || !result.path("tree").isArray()) invalid();
        return result;
    }
    private String blob(JsonNode tree,String path) {
        var matches=new ArrayList<JsonNode>();
        tree.path("tree").forEach(entry -> { if (entry.path("path").asText().equals(path)) matches.add(entry); });
        if (matches.size()!=1 || (!text(matches.getFirst(),"type").equals("blob") || !text(matches.getFirst(),"mode").equals("100644"))) invalid();
        return text(matches.getFirst(),"sha");
    }
    private void verifyArtifact(JsonNode metadata,long id,Archive archive,String arm,JsonNode run,JsonNode pr) {
        var artifact=archive.artifact(); var workflow=metadata.path("workflow_run");
        if (integer(metadata,"id")!=id || !text(metadata,"name").equals("regresslab-"+arm+"-"+artifact.run().runAttempt())
                || !metadata.path("expired").isBoolean() || metadata.path("expired").booleanValue()
                || integer(metadata,"size_in_bytes")>GithubArchiveParser.MAX_ARCHIVE_BYTES
                || integer(workflow,"id")!=artifact.run().workflowRunId() || integer(workflow,"repository_id")!=artifact.repository().githubId()
                || integer(workflow,"head_repository_id")!=integer(pr.path("head").path("repo"),"id")
                || !text(workflow,"head_sha").equals(artifact.pullRequest().candidateSha())
                || !text(metadata,"digest").equals("sha256:"+Digests.bytes(archive.archiveBytes()))) invalid();
        try {
            if (artifact.run().measuredAt().isBefore(Instant.parse(text(run,"created_at")).minusSeconds(60))
                    || artifact.run().measuredAt().isAfter(Instant.parse(text(run,"updated_at")).plusSeconds(60))) invalid();
        } catch (java.time.format.DateTimeParseException exception) { invalid(); }
    }
    private static String text(JsonNode node,String field) { if (!node.path(field).isTextual()) invalid(); return node.path(field).textValue(); }
    private static long integer(JsonNode node,String field) {
        var value=node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1 || value.longValue()>9007199254740991L) invalid();
        return value.longValue();
    }
    private static void invalid() { throw ApiProblem.invalid("GitHub metadata, digest, PR snapshot, or reviewed pipeline does not match the artifact."); }
}
