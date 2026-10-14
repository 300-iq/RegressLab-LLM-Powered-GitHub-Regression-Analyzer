import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError, queryHref, type Query } from "@/lib/results";
import type { BenchmarkCase, Page } from "@/lib/contracts";
import { direction } from "@/lib/presentation";

export function EmptyState({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="data-empty">
      <span className="empty-symbol" aria-hidden="true">
        ◇
      </span>
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}
export function resultFailure(error: unknown) {
  if (error instanceof ApiError && error.kind === "missing") notFound();
  if (!(error instanceof ApiError)) throw error;
  return (
    <section className="panel content-panel" role="alert">
      <p className="eyebrow">DATA UNAVAILABLE</p>
      <h1>Measurements could not be loaded</h1>
      <p>
        {error.kind === "invalid"
          ? "The API response did not match the supported contract."
          : "The performance API or database is unavailable."}{" "}
        No results have been substituted.
      </p>
      <p>Reload this page to retry.</p>
      <Link className="button button-secondary" href="/">
        Back to overview
      </Link>
    </section>
  );
}
export function MetricTag({ benchmark }: { benchmark: BenchmarkCase }) {
  return (
    <span className="metric-tag">
      <strong>{benchmark.unit}</strong>
      <span>· {direction(benchmark.direction)}</span>
    </span>
  );
}
export function OriginBadge({ origin, provenance = "LOCAL_UNVERIFIED" }: { origin: "REAL" | "DEMO"; provenance?: "LOCAL_UNVERIFIED" | "GITHUB_METADATA" }) {
  return (
    <span className={`badge ${origin === "DEMO" ? "badge-demo" : ""}`}>
      {origin === "DEMO" ? "DEMO · Synthetic" : provenance === "GITHUB_METADATA" ? "GitHub metadata checked" : "Provenance unverified"}
    </span>
  );
}
export function GithubLink({
  url,
  origin,
  children,
  provenance = "LOCAL_UNVERIFIED",
}: {
  url: string;
  origin: "REAL" | "DEMO";
  provenance?: "LOCAL_UNVERIFIED" | "GITHUB_METADATA";
  children: React.ReactNode;
}) {
  if (origin === "DEMO")
    return (
      <span className="claimed-link">
        {children} <span>(fictional demo resource)</span>
      </span>
    );
  return (
    <a href={url} target="_blank" rel="noreferrer" className="text-link">
      {children} ↗ <span className="muted">{provenance === "GITHUB_METADATA" ? "Metadata checked" : "Unverified"}</span>
    </a>
  );
}
export function Panel({
  title,
  description,
  action,
  children,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="panel data-panel">
      <div className="panel-header">
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}
export function Pagination({
  data,
  path,
  query,
  parameter = "page",
  label = "results",
}: {
  data: Page<unknown>;
  path: string;
  query: Query;
  parameter?: string;
  label?: string;
}) {
  const pages = Math.ceil(data.totalElements / data.size);
  if (pages <= 1) return null;
  return (
    <nav className="pagination" aria-label={`${label} pagination`}>
      <span>
        Page {data.page + 1} of {pages} ·{" "}
        {data.totalElements.toLocaleString("en-US")} {label}
      </span>
      <div>
        {data.page > 0 ? (
          <Link
            className="button button-secondary"
            href={queryHref(path, query, { [parameter]: data.page - 1 })}
          >
            Previous
          </Link>
        ) : (
          <span aria-disabled="true" className="button disabled">
            Previous
          </span>
        )}
        {data.page + 1 < pages ? (
          <Link
            className="button button-secondary"
            href={queryHref(path, query, { [parameter]: data.page + 1 })}
          >
            Next
          </Link>
        ) : (
          <span aria-disabled="true" className="button disabled">
            Next
          </span>
        )}
      </div>
    </nav>
  );
}
export function Breadcrumb({
  repositoryId,
  repositoryName,
  current,
}: {
  repositoryId: string;
  repositoryName: string;
  current: string;
}) {
  return (
    <nav aria-label="Breadcrumb" className="breadcrumb">
      <Link href="/repositories">Repositories</Link>
      <span>/</span>
      <Link href={`/repositories/${repositoryId}`}>{repositoryName}</Link>
      <span>/</span>
      <span aria-current="page">{current}</span>
    </nav>
  );
}
export function CaseFilter({
  cases,
  selected,
  hidden = {},
}: {
  cases: BenchmarkCase[];
  selected?: string;
  hidden?: Record<string, string>;
}) {
  return (
    <form className="filter-form">
      <div className="field">
        <label htmlFor="case-filter">Benchmark case</label>
        <select id="case-filter" name="case" defaultValue={selected ?? ""}>
          <option value="">Select a benchmark</option>
          {cases.map((benchmark) => (
            <option key={benchmark.id} value={benchmark.id}>
              {benchmark.identifier} · {benchmark.unit}
            </option>
          ))}
        </select>
      </div>
      {Object.entries(hidden).map(([key, value]) => (
        <input key={key} type="hidden" name={key} value={value} />
      ))}
      <button className="button button-primary" type="submit">
        Apply filter
      </button>
    </form>
  );
}
