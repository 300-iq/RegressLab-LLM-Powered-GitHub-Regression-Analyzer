package dev.regresslab.results;

import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import dev.regresslab.persistence.BenchmarkRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ResultsService {
    private final BenchmarkRepository repository;
    public ResultsService(BenchmarkRepository repository) { this.repository = repository; }
    public OverviewDto overview() { return repository.overview(); }
    public List<RepositoryDto> repositories() { return repository.repositories(); }
    public Page<PullRequestDto> prs(UUID repo, DataOrigin origin, int page, int size) {
        repository.repository(repo); return repository.prs(repo, origin, page, size);
    }
    public Page<RunDto> runs(UUID repo, DataOrigin origin, int page, int size) {
        repository.repository(repo); return repository.runs(repo, null, origin, page, size);
    }
    public RunDetail run(UUID run) { return new RunDetail(repository.run(run), repository.runBenchmarks(run)); }
    public PrDetail pr(UUID prId, int page, int size) {
        var pr = repository.pr(prId);
        var repo = repository.repository(pr.repositoryId());
        return new PrDetail(pr, repository.runs(repo.id(), prId, repo.dataOrigin(), page, size), repository.comparisons(prId, page, size));
    }
    public Page<RunDto> prRuns(UUID prId, int page, int size) {
        var pr = repository.pr(prId); var repo = repository.repository(pr.repositoryId());
        return repository.runs(repo.id(), prId, repo.dataOrigin(), page, size);
    }
    public SamplePage samples(UUID run, UUID caseId, int page, int size) {
        var r = repository.run(run); var b = repository.runBenchmark(run, caseId);
        return new SamplePage(run, caseId, b.benchmarkCase().unit(), r.dataOrigin(), repository.samples(run, caseId, page, size));
    }
    public Page<HistoryPoint> history(UUID caseId, DataOrigin origin, String env, String config, int page, int size) {
        repository.benchmarkCase(caseId); return repository.history(caseId, origin, env, config, page, size);
    }
    public ComparisonDto comparison(UUID id) { return repository.comparison(id); }
    public Page<ComparisonDto> comparisons(UUID prId, int page, int size) { repository.pr(prId); return repository.comparisons(prId, page, size); }
}
