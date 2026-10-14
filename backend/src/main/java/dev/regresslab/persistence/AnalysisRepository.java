package dev.regresslab.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.analysis.AnalysisDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import dev.regresslab.domain.ApiDtos.Page;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectMapper storedReader;
    public AnalysisRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper; this.storedReader = StoredJson.reader(mapper);
    }
    private RowMapper<AnalysisDto> rowMapper() {
        return (r, n) -> {
            try {
                return new AnalysisDto(r.getObject("id", UUID.class), r.getObject("comparison_id", UUID.class),
                        r.getString("analysis_version"), r.getString("policy_sha256"), r.getString("input_sha256"),
                        storedReader.readValue(r.getString("policy"), Policy.class), storedReader.readValue(r.getString("result"), Result.class),
                        DataOrigin.valueOf(r.getString("data_origin")), r.getString("provenance"),
                        r.getTimestamp("created_at").toInstant());
            } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted comparison analysis", e); }
        };
    }
    public AnalysisDto get(UUID id) {
        var rows = jdbc.query("SELECT * FROM comparison_analyses WHERE id = ?", rowMapper(), id);
        if (rows.isEmpty()) throw ApiProblem.notFound("Comparison analysis was not found.");
        return rows.getFirst();
    }
    public Page<AnalysisDto> list(UUID comparison, int page, int size) {
        var rows = jdbc.query("SELECT * FROM comparison_analyses WHERE comparison_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                rowMapper(), comparison, size, page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM comparison_analyses WHERE comparison_id = ?", Long.class, comparison);
        return new Page<>(rows, page, size, total);
    }
    public AnalysisDto find(UUID comparison, String version, String policyHash, String inputHash) {
        var rows = jdbc.query("SELECT * FROM comparison_analyses WHERE comparison_id = ? AND analysis_version = ? AND policy_sha256 = ? AND input_sha256 = ?",
                rowMapper(), comparison, version, policyHash, inputHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public AnalysisDto insert(UUID comparison, String version, String policyHash, String inputHash, Policy policy, Result result,
                              DataOrigin origin, String provenance) {
        try {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO comparison_analyses(comparison_id, analysis_version, policy_sha256, input_sha256, policy, result, data_origin, provenance)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) RETURNING id
                    """, UUID.class, comparison, version, policyHash, inputHash, mapper.writeValueAsString(policy), mapper.writeValueAsString(result), origin.name(), provenance);
            return get(id);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize comparison analysis", e); }
    }
}
