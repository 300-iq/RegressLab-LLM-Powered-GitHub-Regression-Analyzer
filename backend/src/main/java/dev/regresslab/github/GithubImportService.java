package dev.regresslab.github;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.github.GithubDtos.*;
import org.springframework.stereotype.Service;
@Service
public class GithubImportService {
    private final GithubSettings settings;
    private final GithubArchiveParser parser;
    private final GithubVerifier verifier;
    private final GithubPersistence persistence;
    public GithubImportService(GithubSettings settings,GithubArchiveParser parser,GithubVerifier verifier,GithubPersistence persistence) {
        this.settings=settings; this.parser=parser; this.verifier=verifier; this.persistence=persistence;
    }
    public ImportPair ingest(ImportRequest request) {
        if (!settings.enabled()) throw ApiProblem.notFound("GitHub artifact imports are disabled.");
        byte[] baseline=parser.decode(request.baselineArchiveChunks()), candidate=parser.decode(request.candidateArchiveChunks());
        if (baseline.length+candidate.length>GithubArchiveParser.MAX_ARCHIVE_BYTES) throw ApiProblem.invalid("Archive pair exceeds 512 KiB compressed.");
        // All external I/O and validation occur before a DB transaction/lock. Both
        // checked archives and their audit records commit in one atomic transaction.
        var checked=verifier.verify(request.workflowRunId(),request.baselineArtifactId(),request.candidateArtifactId(),parser.parse(baseline),parser.parse(candidate));
        return persistence.persist(checked);
    }
}
