package dev.regresslab.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BenchmarkRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectMapper storedReader;
    public BenchmarkRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper; this.storedReader = StoredJson.reader(mapper);
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize stored metadata", exception); }
    }
    private JsonNode jsonNode(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid persisted JSON", exception); }
    }
    private static UUID id(ResultSet rs, String name) throws SQLException { return rs.getObject(name, UUID.class); }
    private static Instant time(ResultSet rs, String name) throws SQLException { return rs.getTimestamp(name).toInstant(); }
    private <T> T one(List<T> rows, String name) { if (rows.isEmpty()) throw ApiProblem.notFound(name + " was not found."); return rows.getFirst(); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    public void lockWrites() { jdbc.execute("SELECT pg_advisory_xact_lock(726547831)"); }
    public OverviewDto overview() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM repositories) AS repositories,
                       (SELECT count(*) FROM pull_requests) AS pull_requests,
                       ((SELECT count(*) FROM comparisons WHERE status = 'COMPLETE') +
                        (SELECT count(*) FROM run_comparisons WHERE analysis_status <> 'INCOMPATIBLE')) AS comparisons,
                       (SELECT count(*) FROM observations o
                        JOIN comparison_benchmarks cb ON cb.id = o.comparison_benchmark_id
                        JOIN comparisons c ON c.id = cb.comparison_id WHERE c.status = 'COMPLETE') +
                       (SELECT count(*) FROM benchmark_samples s JOIN benchmark_runs r ON r.id = s.run_id
                        WHERE r.status = 'COMPLETE') AS observations
                """, (r, n) -> new OverviewDto(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4)));
    }
    public List<RepositoryDto> repositories() { return jdbc.query("SELECT * FROM repositories ORDER BY full_name", repoMapper()); }
    public long githubRepositoryId(UUID id) { return jdbc.queryForObject("SELECT github_id FROM repositories WHERE id = ?", Long.class, id); }
    public RepositoryDto repository(UUID repositoryId) { return one(jdbc.query("SELECT * FROM repositories WHERE id = ?", repoMapper(), repositoryId), "Repository"); }
    private RowMapper<RepositoryDto> repoMapper() {
        return (r, n) -> new RepositoryDto(id(r, "id"), r.getString("full_name"), r.getString("github_url"),
                r.getString("owner"), r.getString("name"), DataOrigin.valueOf(r.getString("data_origin")), r.getString("provenance"));
    }
    public UUID ensureRepository(RepositoryInput input, DataOrigin origin) {
        var existing = repositories();
        if (!existing.isEmpty()) {
            var repo = existing.getFirst();
            Long githubId = jdbc.queryForObject("SELECT github_id FROM repositories WHERE id = ?", Long.class, repo.id());
            if (!githubId.equals(input.githubId()) || !repo.fullName().equals(input.fullName()) || repo.dataOrigin() != origin)
                throw ApiProblem.conflict("The database already contains a different repository or data origin.");
            return repo.id();
        }
        return jdbc.queryForObject("INSERT INTO repositories(github_id, full_name, data_origin) VALUES (?, ?, ?) RETURNING id",
                UUID.class, input.githubId(), input.fullName(), origin.name());
    }
    public UUID upsertPr(UUID repoId, PullRequestInput input) {
        return jdbc.queryForObject("""
                INSERT INTO pull_requests(repository_id, number, title, state, baseline_sha, candidate_sha)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (repository_id, number) DO UPDATE SET title = EXCLUDED.title,
                    state = EXCLUDED.state, baseline_sha = EXCLUDED.baseline_sha,
                    candidate_sha = EXCLUDED.candidate_sha, provenance = 'LOCAL_UNVERIFIED', updated_at = now() RETURNING id
                """, UUID.class, repoId, input.number(), input.title(), input.state().name(), input.baselineSha(), input.candidateSha());
    }
    private RowMapper<PullRequestDto> prMapper() {
        return (r, n) -> new PullRequestDto(id(r, "id"), id(r, "repository_id"), r.getInt("number"), r.getString("title"),
                "https://github.com/" + r.getString("full_name") + "/pull/" + r.getInt("number"), r.getString("state"),
                r.getString("baseline_sha"), r.getString("candidate_sha"), time(r, "updated_at"),
                DataOrigin.valueOf(r.getString("data_origin")), r.getString("provenance"));
    }
    private static final String PR_SELECT = "SELECT p.*, r.full_name, r.data_origin FROM pull_requests p JOIN repositories r ON r.id = p.repository_id ";
    public PullRequestDto pr(UUID prId) { return one(jdbc.query(PR_SELECT + "WHERE p.id = ?", prMapper(), prId), "Pull request"); }
    public Page<PullRequestDto> prs(UUID repo, DataOrigin origin, int page, int size) {
        String where = " WHERE p.repository_id = ? AND r.data_origin = ?";
        return new Page<>(jdbc.query(PR_SELECT + where + " ORDER BY p.number DESC LIMIT ? OFFSET ?", prMapper(), repo, origin.name(), size, page * size),
                page, size, count("SELECT count(*) FROM pull_requests p JOIN repositories r ON r.id = p.repository_id" + where, repo, origin.name()));
    }
    private static final String RUN_SELECT = """
            SELECT b.*, a.artifact_identifier, a.sha256 AS artifact_sha256, r.full_name
            FROM benchmark_runs b JOIN ingested_artifacts a ON a.id = b.artifact_id
            JOIN repositories r ON r.id = b.repository_id
            """;
    private RowMapper<RunDto> runMapper() {
        return (r, n) -> new RunDto(id(r, "id"), id(r, "repository_id"), id(r, "pull_request_id"), r.getLong("workflow_run_id"),
                r.getInt("workflow_run_attempt"), "https://github.com/" + r.getString("full_name") + "/actions/runs/" + r.getLong("workflow_run_id"),
                r.getString("commit_sha"), r.getString("baseline_sha"), r.getString("candidate_sha"), r.getString("branch"), time(r, "measured_at"),
                jsonNode(r.getString("environment")), r.getString("environment_sha256"), RunStatus.valueOf(r.getString("status")),
                DataOrigin.valueOf(r.getString("data_origin")), r.getString("provenance"), id(r, "artifact_id"), r.getString("artifact_identifier"),
                r.getString("artifact_sha256"), time(r, "imported_at"));
    }
    public RunDto run(UUID runId) { return one(jdbc.query(RUN_SELECT + " WHERE b.id = ?", runMapper(), runId), "Benchmark run"); }
    public RunDto findArtifact(UUID repo, String artifactIdentifier) {
        var rows = jdbc.query(RUN_SELECT + " WHERE b.repository_id = ? AND a.artifact_identifier = ?", runMapper(), repo, artifactIdentifier);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public boolean runExists(UUID repo, RunInput input) {
        return count("SELECT count(*) FROM benchmark_runs WHERE repository_id = ? AND workflow_run_id = ? AND workflow_run_attempt = ? AND commit_sha = ?",
                repo, input.workflowRunId(), input.runAttempt(), input.commitSha()) != 0;
    }
    public Page<RunDto> runs(UUID repo, UUID prId, DataOrigin origin, int page, int size) {
        String where = " WHERE b.repository_id = ? AND b.data_origin = ?" + (prId == null ? "" : " AND b.pull_request_id = ?");
        Object[] args = prId == null ? new Object[]{repo, origin.name()} : new Object[]{repo, origin.name(), prId};
        var paged = new java.util.ArrayList<>(List.of(args)); paged.add(size); paged.add(page * size);
        return new Page<>(jdbc.query(RUN_SELECT + where + " ORDER BY b.measured_at DESC, b.id LIMIT ? OFFSET ?", runMapper(), paged.toArray()),
                page, size, count("SELECT count(*) FROM benchmark_runs b" + where, args));
    }
    public UUID insertArtifact(UUID repo, Artifact input, byte[] bytes, String digest) {
        return jdbc.queryForObject("INSERT INTO ingested_artifacts(repository_id, artifact_identifier, sha256, schema_version, raw_json) VALUES (?, ?, ?, 2, ?) RETURNING id",
                UUID.class, repo, input.artifactId(), digest, bytes);
    }
    public UUID insertRun(UUID repo, UUID prId, UUID artifactId, Artifact input, String environmentHash) {
        var run = input.run(); var pr = input.pullRequest();
        return jdbc.queryForObject("""
                INSERT INTO benchmark_runs(repository_id, pull_request_id, artifact_id, workflow_run_id, workflow_run_attempt,
                    commit_sha, baseline_sha, candidate_sha, branch, measured_at, environment, environment_sha256, status, data_origin)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id
                """, UUID.class, repo, prId, artifactId, run.workflowRunId(), run.runAttempt(), run.commitSha(), pr.baselineSha(), pr.candidateSha(),
                run.branch(), java.sql.Timestamp.from(run.measuredAt()), json(run.environment()), environmentHash, run.status().name(), input.dataOrigin().name());
    }
    private RowMapper<CaseDto> caseMapper() {
        return (r, n) -> new CaseDto(id(r, "id"), id(r, "repository_id"), r.getString("identifier"), r.getString("metric_name"),
                r.getString("unit"), Direction.valueOf(r.getString("direction")));
    }
    public UUID ensureCase(UUID repo, CaseInput input) {
        var cases = jdbc.query("SELECT * FROM benchmark_cases WHERE repository_id = ? AND identifier = ?", caseMapper(), repo, input.identifier());
        if (!cases.isEmpty()) {
            var c = cases.getFirst();
            if (!c.metricName().equals(input.metricName()) || !c.unit().equals(input.unit()) || c.direction() != input.direction())
                throw ApiProblem.conflict("A stable benchmark case cannot change metric, unit or direction.");
            return c.id();
        }
        return jdbc.queryForObject("INSERT INTO benchmark_cases(repository_id, identifier, metric_name, unit, direction) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, repo, input.identifier(), input.metricName(), input.unit(), input.direction().name());
    }
    public CaseDto benchmarkCase(UUID caseId) { return one(jdbc.query("SELECT * FROM benchmark_cases WHERE id = ?", caseMapper(), caseId), "Benchmark case"); }
    public void insertBenchmark(UUID repo, UUID run, UUID caseId, CaseInput input, String hash) {
        jdbc.update("INSERT INTO run_benchmarks(run_id, case_id, repository_id, parameters, configuration, configuration_sha256) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                run, caseId, repo, json(input.parameters()), json(input.configuration()), hash);
        jdbc.batchUpdate("INSERT INTO benchmark_samples(run_id, case_id, fork_index, iteration_index, value) VALUES (?, ?, ?, ?, ?)",
                input.samples(), 300, (statement, sample) -> {
                    statement.setObject(1, run); statement.setObject(2, caseId);
                    statement.setInt(3, sample.forkIndex()); statement.setInt(4, sample.iterationIndex()); statement.setDouble(5, sample.value());
                });
    }
    public List<RunBenchmarkDto> runBenchmarks(UUID run) {
        return jdbc.query("""
                SELECT c.*, rb.parameters, rb.configuration, rb.configuration_sha256,
                    (SELECT count(*) FROM benchmark_samples s WHERE s.run_id = rb.run_id AND s.case_id = rb.case_id) AS sample_count
                FROM run_benchmarks rb JOIN benchmark_cases c ON c.id = rb.case_id
                WHERE rb.run_id = ? ORDER BY c.identifier
                """, (r, n) -> new RunBenchmarkDto(caseMapper().mapRow(r, n), jsonNode(r.getString("parameters")), jsonNode(r.getString("configuration")),
                r.getString("configuration_sha256"), r.getLong("sample_count")), run);
    }
    public RunBenchmarkDto runBenchmark(UUID run, UUID caseId) {
        return runBenchmarks(run).stream().filter(b -> b.benchmarkCase().id().equals(caseId)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("That benchmark case is not present in the run."));
    }
    public List<SampleDto> allSamples(UUID run, UUID caseId) {
        return jdbc.query("SELECT fork_index, iteration_index, value FROM benchmark_samples WHERE run_id = ? AND case_id = ? ORDER BY fork_index, iteration_index",
                (r, n) -> new SampleDto(r.getInt(1), r.getInt(2), r.getDouble(3)), run, caseId);
    }
    public Page<SampleDto> samples(UUID run, UUID caseId, int page, int size) {
        return new Page<>(jdbc.query("SELECT fork_index, iteration_index, value FROM benchmark_samples WHERE run_id = ? AND case_id = ? ORDER BY fork_index, iteration_index LIMIT ? OFFSET ?",
                (r, n) -> new SampleDto(r.getInt(1), r.getInt(2), r.getDouble(3)), run, caseId, size, page * size), page, size,
                count("SELECT count(*) FROM benchmark_samples WHERE run_id = ? AND case_id = ?", run, caseId));
    }
    private RowMapper<ComparisonDto> comparisonMapper() {
        return (r, n) -> {
            try {
                Summary summary = r.getString("summary") == null ? null : storedReader.readValue(r.getString("summary"), Summary.class);
                List<String> reasons = mapper.readValue(r.getString("reasons"), mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                return new ComparisonDto(id(r, "id"), id(r, "repository_id"), id(r, "baseline_run_id"), id(r, "candidate_run_id"), id(r, "case_id"),
                        r.getString("analysis_version"), r.getString("analysis_status"), summary, reasons,
                        DataOrigin.valueOf(r.getString("data_origin")), r.getString("provenance"), time(r, "created_at"));
            } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted analysis", e); }
        };
    }
    private static final String COMPARISON_SELECT = "SELECT c.*, b.data_origin FROM run_comparisons c JOIN benchmark_runs b ON b.id = c.candidate_run_id ";
    public ComparisonDto comparison(UUID comparisonId) { return one(jdbc.query(COMPARISON_SELECT + " WHERE c.id = ?", comparisonMapper(), comparisonId), "Comparison"); }
    public ComparisonDto findComparison(ComparisonRequest request, String version) {
        var rows = jdbc.query(COMPARISON_SELECT + " WHERE c.baseline_run_id = ? AND c.candidate_run_id = ? AND c.case_id = ? AND c.analysis_version = ?",
                comparisonMapper(), request.baselineRunId(), request.candidateRunId(), request.benchmarkCaseId(), version);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public ComparisonDto insertComparison(UUID repo, ComparisonRequest input, String version, String status, Summary summary, List<String> reasons) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO run_comparisons(repository_id, baseline_run_id, candidate_run_id, case_id, analysis_version, analysis_status, summary, reasons, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                    CASE WHEN (SELECT count(*) FROM benchmark_runs WHERE id IN (?,?) AND provenance='GITHUB_METADATA') = 2 THEN 'GITHUB_METADATA' ELSE 'LOCAL_UNVERIFIED' END) RETURNING id
                """, UUID.class, repo, input.baselineRunId(), input.candidateRunId(), input.benchmarkCaseId(), version, status,
                summary == null ? null : json(summary), json(reasons), input.baselineRunId(), input.candidateRunId());
        return comparison(id);
    }
    public Page<ComparisonDto> comparisons(UUID prId, int page, int size) {
        return new Page<>(jdbc.query(COMPARISON_SELECT + " WHERE b.pull_request_id = ? ORDER BY c.created_at DESC, c.id LIMIT ? OFFSET ?",
                comparisonMapper(), prId, size, page * size), page, size,
                count("SELECT count(*) FROM run_comparisons c JOIN benchmark_runs b ON b.id = c.candidate_run_id WHERE b.pull_request_id = ?", prId));
    }
    public Page<HistoryPoint> history(UUID caseId, DataOrigin origin, String environmentHash, String configurationHash, int page, int size) {
        String where = " WHERE rb.case_id = ? AND b.data_origin = ? AND b.status = 'COMPLETE'";
        var args = new java.util.ArrayList<Object>(); args.add(caseId); args.add(origin.name());
        if (environmentHash != null) { where += " AND b.environment_sha256 = ?"; args.add(environmentHash); }
        if (configurationHash != null) { where += " AND rb.configuration_sha256 = ?"; args.add(configurationHash); }
        long total = count("SELECT count(*) FROM run_benchmarks rb JOIN benchmark_runs b ON b.id = rb.run_id" + where, args.toArray());
        args.add(size); args.add(page * size);
        String sql = RUN_SELECT.replace("SELECT b.*", "SELECT rb.parameters, rb.configuration, rb.configuration_sha256, stats.*, b.*")
                + " JOIN run_benchmarks rb ON rb.run_id = b.id " + """
                JOIN LATERAL (
                    SELECT count(*) AS forks, sum(n) AS sample_count, avg(fork_mean) AS mean
                    FROM (SELECT avg(s.value) AS fork_mean, count(*) AS n FROM benchmark_samples s
                          WHERE s.run_id = b.id AND s.case_id = rb.case_id GROUP BY s.fork_index) f
                ) stats ON true
                """ + where + " ORDER BY b.measured_at DESC, b.id LIMIT ? OFFSET ?";
        var points = jdbc.query(sql, (r, n) -> new HistoryPoint(runMapper().mapRow(r, n), jsonNode(r.getString("parameters")),
                jsonNode(r.getString("configuration")), r.getString("configuration_sha256"), r.getInt("forks"),
                r.getLong("sample_count"), r.getDouble("mean")), args.toArray());
        return new Page<>(points, page, size, total);
    }
}
