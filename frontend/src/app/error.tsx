"use client";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return <div role="alert" className="panel content-panel"><h2>The workspace could not be loaded</h2><p>Please retry. No results have been substituted.</p><button className="button button-primary" onClick={reset}>Try again</button></div>;
}
