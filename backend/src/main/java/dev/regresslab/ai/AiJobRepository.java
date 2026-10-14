package dev.regresslab.ai;

import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.Page;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class AiJobRepository {
    private final JdbcTemplate jdbc;
    public AiJobRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Enqueued(JobDto job, boolean created) {}
    private final RowMapper<JobDto> rows=(r,n) -> new JobDto(r.getObject("id",UUID.class),r.getObject("comparison_id",UUID.class),r.getObject("analysis_id",UUID.class),
            JobStatus.valueOf(r.getString("status")),r.getObject("report_id",UUID.class),r.getBoolean("reused"),r.getString("error_code"),r.getString("detail"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public JobDto get(UUID id) {
        var found=jdbc.query("SELECT * FROM ai_investigation_jobs WHERE id=?",rows,id);
        if(found.isEmpty()) throw ApiProblem.notFound("Investigation job was not found.");
        return found.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<JobDto> list(UUID comparison,int page,int size) {
        return new Page<>(jdbc.query("SELECT * FROM ai_investigation_jobs WHERE comparison_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",rows,comparison,size,page*size),page,size,
                jdbc.queryForObject("SELECT count(*) FROM ai_investigation_jobs WHERE comparison_id=?",Long.class,comparison));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Enqueued enqueue(UUID comparison,UUID analysis,String hash) {
        jdbc.execute("SELECT pg_advisory_xact_lock(20261014,3)");
        var active=jdbc.query("SELECT * FROM ai_investigation_jobs WHERE status IN ('QUEUED','RUNNING')",rows);
        if(!active.isEmpty()) {
            String stored=jdbc.queryForObject("SELECT request_sha256 FROM ai_investigation_jobs WHERE id=?",String.class,active.getFirst().id());
            if(stored.equals(hash)) return new Enqueued(active.getFirst(),false);
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS,"AI_BUSY","One investigation is already active. No additional inference was queued.");
        }
        UUID id=jdbc.queryForObject("INSERT INTO ai_investigation_jobs(comparison_id,analysis_id,request_sha256,status) VALUES (?,?,?,'QUEUED') RETURNING id",UUID.class,comparison,analysis,hash);
        return new Enqueued(get(id),true);
    }
    public String requestHash(UUID id) { return jdbc.queryForObject("SELECT request_sha256 FROM ai_investigation_jobs WHERE id=?",String.class,id); }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void running(UUID id) {
        if(jdbc.update("UPDATE ai_investigation_jobs SET status='RUNNING',updated_at=now() WHERE id=? AND status='QUEUED'",id)!=1)
            throw new IllegalStateException("Job is not queued");
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public JobDto complete(UUID id,Supplier<ReportDto> save,boolean reused) {
        var report=save.get(); // Only persistence: no network or inference inside this transaction.
        if(jdbc.update("UPDATE ai_investigation_jobs SET status='SUCCEEDED',report_id=?,reused=?,updated_at=now() WHERE id=? AND status='RUNNING'",report.id(),reused,id)!=1)
            throw new IllegalStateException("Job completion lost its running state");
        return get(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void fail(UUID id,String code,String detail) {
        jdbc.update("UPDATE ai_investigation_jobs SET status='FAILED',error_code=?,detail=?,updated_at=now() WHERE id=? AND status IN ('QUEUED','RUNNING')",code,detail,id);
    }
    /** Dedicated session lock, autocommit, no open transaction during inference. */
    public boolean withWorkerLock(Runnable action) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if(!connection.getAutoCommit()) throw new IllegalStateException("Worker lock must run outside a transaction");
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(3);
                try(var result=statement.executeQuery("SELECT pg_try_advisory_lock(20261014,4)")) { result.next(); if(!result.getBoolean(1)) return false; }
                try { action.run(); }
                finally { statement.execute("SELECT pg_advisory_unlock(20261014,4)"); }
                return true;
            }
        }));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void recoverInterrupted() {
        // Caller owns the session lock: a live worker cannot be failed here. Fresh queues get a scheduling grace period.
        jdbc.update("UPDATE ai_investigation_jobs SET status='FAILED',error_code='AI_JOB_INTERRUPTED',detail='The worker stopped before completing this job. Existing reports are unchanged; submit explicitly to retry.',updated_at=now() WHERE status='RUNNING' OR (status='QUEUED' AND created_at < now()-interval '60 seconds')");
    }
}
