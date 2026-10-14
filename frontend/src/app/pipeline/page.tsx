import { PageHeading } from "@/components/page-heading";
const steps = [
  ["01", "Benchmark the exact commit pair", "Java 21 / JMH executes real baseline and candidate event queries in an unprivileged pull_request job. Three fresh forks per case and arm each run three warmups and five measurement iterations."],
  ["02", "Preserve the observations", "Baseline/candidate fork blocks alternate. Two Actions artifacts preserve raw iterations, exact SHAs, environment, configuration and an execution ledger with reported failures and timeouts."],
  ["03", "Administratively import checked artifacts", "A trusted administrator downloads the original ZIPs. The loopback-only API checks GitHub metadata, reviewed pipeline files and archive digests before atomically writing both arms to PostgreSQL."],
  ["04", "Investigate descriptive differences", "Inspect all raw samples, fork means, execution order and compatible history. Three-fork results remain inconclusive. Stored bootstrap intervals are exploratory and do not establish significance or causation."],
];
export default function PipelinePage() {
  return (
    <>
      <PageHeading eyebrow="WORKSPACE / INTEGRATION" title="A traceable benchmark pipeline." description="Real Java measurements from one public demonstration repository." />
      <div className="callout"><strong>Public demonstration: event aggregation queries.</strong><p>The workload is intentionally constructed. Its observations come from executed code; they are not production traffic or synthetic development fixtures.</p><a className="text-link" href="https://github.com/300-iq/regresslab-java-demo" target="_blank" rel="noreferrer">View demonstration repository ↗</a></div>
      <section className="panel content-panel"><h2>Implemented data flow</h2><ol className="pipeline-steps">{steps.map(([number,title,description])=><li key={number}><span>{number}</span><div><h3>{title}</h3><p>{description}</p></div><span className="badge">Implemented</span></li>)}</ol></section>
      <section className="panel content-panel prose"><h2>Import and execution limits</h2><p>Artifact import is an explicit local administrative operation. The frontend holds no ingestion or GitHub credentials. A new workflow result does not appear until it is imported.</p><p>GitHub metadata checks establish artifact origin and the reviewed commit pair. Untrusted PR code shares the runner and can influence results; matching metadata does not guarantee honest measurements or remove hosted runner noise.</p><p>The repository README and GitHub integration guide record download/import commands, the actual accepted PR and workflow run, security boundaries and retention limits.</p></section>
    </>
  );
}
