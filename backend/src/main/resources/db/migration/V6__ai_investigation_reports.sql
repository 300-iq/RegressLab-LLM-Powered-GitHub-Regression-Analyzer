-- Model hypotheses never overwrite measured comparisons or statistical analyses.
ALTER TABLE comparison_analyses ADD CONSTRAINT analysis_id_comparison_unique UNIQUE (id,comparison_id);
CREATE TABLE ai_investigation_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    comparison_id uuid NOT NULL REFERENCES run_comparisons(id),
    analysis_id uuid NOT NULL,
    report_version varchar(50) NOT NULL,
    input_sha256 char(64) NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    model varchar(100) NOT NULL,
    model_digest char(64) NOT NULL CHECK (model_digest ~ '^[0-9a-f]{64}$'),
    status varchar(30) NOT NULL CHECK (status IN ('NO_HYPOTHESES','UNVERIFIED_HYPOTHESES')),
    input jsonb NOT NULL CHECK (jsonb_typeof(input)='object' AND octet_length(input::text) <= 65536),
    report jsonb NOT NULL CHECK ((jsonb_typeof(report)='object' AND jsonb_typeof(report->'hypotheses')='array'
        AND jsonb_array_length(report->'hypotheses') <= 5 AND jsonb_typeof(report->'missingEvidence')='array'
        AND jsonb_array_length(report->'missingEvidence') BETWEEN 1 AND 10) IS TRUE),
    raw_response text NOT NULL CHECK (octet_length(raw_response) <= 65536),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (analysis_id,comparison_id) REFERENCES comparison_analyses(id,comparison_id),
    CHECK ((status='NO_HYPOTHESES') = (jsonb_array_length(report->'hypotheses')=0))
);
CREATE INDEX ai_reports_comparison_created ON ai_investigation_reports(comparison_id,created_at DESC,id DESC);
