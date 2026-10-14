package dev.regresslab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.Page;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AiReportRepository {
    public static final List<String> LIMITATIONS = List.of(
            "UNVERIFIED_HYPOTHESES: Model-generated prose is not a verified root cause or statistical conclusion.",
            "Only supplied changed lines and comparison identifiers are mechanically checked. Prose may still be wrong or misleading.",
            "Prompt injection defenses do not guarantee faithful interpretation. Repository content is untrusted; no model tools exist.",
            "GitHub artifact origin does not attest honest measurements. Runner noise, dependent iterations and limited forks remain confounders.",
            "Experiments are suggestions and have not been executed. Report applies only to its stored commit/diff/analysis snapshot.");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AiReportRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = AiJson.reader(mapper); }
    private RowMapper<ReportDto> rowMapper() {
        return (r,n) -> {
            try {
                var report=mapper.readValue(r.getString("report"),ModelReport.class);
                var limitations=new java.util.ArrayList<>(LIMITATIONS); limitations.addAll(ReportValidator.reviewWarnings(report));
                return new ReportDto(r.getObject("id",UUID.class),r.getObject("comparison_id",UUID.class),r.getString("report_version"),
                    r.getString("input_sha256"),r.getString("model"),r.getString("model_digest"),r.getString("status"),
                    mapper.readValue(r.getString("input"),Input.class),report,java.util.List.copyOf(limitations),r.getTimestamp("created_at").toInstant()); }
            catch (java.io.IOException e) { throw new IllegalStateException("Invalid stored AI report",e); }
        };
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportDto get(UUID id) {
        var rows = jdbc.query("SELECT * FROM ai_investigation_reports WHERE id = ?",rowMapper(),id);
        if (rows.isEmpty()) throw ApiProblem.notFound("AI investigation report was not found.");
        return rows.getFirst();
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ReportDto> list(UUID comparison, int page, int size) {
        var rows = jdbc.query("SELECT * FROM ai_investigation_reports WHERE comparison_id = ? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",rowMapper(),comparison,size,page * size);
        return new Page<>(rows,page,size,jdbc.queryForObject("SELECT count(*) FROM ai_investigation_reports WHERE comparison_id = ?",Long.class,comparison));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public java.util.Optional<ReportDto> reusable(String hash, ModelIdentity model) {
        var rows=jdbc.query("SELECT * FROM ai_investigation_reports WHERE report_version=? AND input_sha256=? AND model=? AND model_digest=? ORDER BY created_at DESC,id DESC LIMIT 1",
                rowMapper(),OllamaClient.VERSION,hash,model.name(),model.digest());
        return rows.stream().findFirst();
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReportDto insert(UUID comparison, String hash, Input input, Completion completion) {
        try {
            // The network call is complete before opening this short transaction.
            UUID id = jdbc.queryForObject("""
                    INSERT INTO ai_investigation_reports(comparison_id,analysis_id,report_version,input_sha256,model,model_digest,status,input,report,raw_response)
                    VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?) RETURNING id
                    """,UUID.class,comparison,input.evidence().analysis().id(),OllamaClient.VERSION,hash,completion.model().name(),completion.model().digest(),
                    completion.report().hypotheses().isEmpty() ? "NO_HYPOTHESES" : "UNVERIFIED_HYPOTHESES",
                    mapper.writeValueAsString(input),mapper.writeValueAsString(completion.report()),completion.rawResponse());
            return get(id);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot serialize AI report",e); }
    }
}
