-- Immutable reports retained. Jobs track orchestration; completed reports are never overwritten.
ALTER TABLE ai_investigation_reports ADD CONSTRAINT ai_report_job_scope UNIQUE(id,comparison_id,analysis_id);
CREATE INDEX ai_report_reuse ON ai_investigation_reports(report_version,input_sha256,model,model_digest);
CREATE TABLE ai_investigation_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    comparison_id uuid NOT NULL,
    analysis_id uuid NOT NULL,
    request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
    status varchar(20) NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    report_id uuid,
    reused boolean NOT NULL DEFAULT false,
    error_code varchar(100),
    detail varchar(2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(analysis_id,comparison_id) REFERENCES comparison_analyses(id,comparison_id),
    FOREIGN KEY(report_id,comparison_id,analysis_id) REFERENCES ai_investigation_reports(id,comparison_id,analysis_id),
    CHECK((status='SUCCEEDED') = (report_id IS NOT NULL)),
    CHECK((status='FAILED' AND error_code IS NOT NULL AND detail IS NOT NULL) OR (status<>'FAILED' AND error_code IS NULL AND detail IS NULL)),
    CHECK(NOT reused OR status='SUCCEEDED')
);
-- Single active application inference job across all processes using this database.
CREATE UNIQUE INDEX ai_one_active_job ON ai_investigation_jobs((true)) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ai_jobs_comparison_created ON ai_investigation_jobs(comparison_id,created_at DESC,id DESC);
