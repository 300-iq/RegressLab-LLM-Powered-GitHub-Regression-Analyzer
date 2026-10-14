export default function InvestigationLoading() {
  return <section className="inv-loading" role="status" aria-live="polite"><p className="eyebrow">PERFORMANCE INVESTIGATION</p><h1>Loading the evidence…</h1><p>Reading stored comparisons, complete raw measurements and recorded conditions.</p><div className="inv-summary-grid" aria-hidden="true"><span className="inv-skeleton" /><span className="inv-skeleton" /><span className="inv-skeleton" /></div><div className="inv-skeleton inv-skeleton-chart" aria-hidden="true" /></section>;
}
