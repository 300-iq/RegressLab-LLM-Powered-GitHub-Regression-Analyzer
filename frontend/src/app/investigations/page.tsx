import { PageHeading } from "@/components/page-heading";
import {
  EmptyState,
  Pagination,
  Panel,
  resultFailure,
} from "@/components/results/ui";
import { PrTable } from "@/components/results/tables";
import {
  attempt,
  getPrs,
  getRepositories,
  pageNumber,
  type Query,
} from "@/lib/results";
async function loadInvestigations(props: { searchParams: Promise<Query> }) {
  const query = await props.searchParams,
    repos = await getRepositories();
  const repo = repos[0],
    prs = repo ? await getPrs(repo, pageNumber(query)) : null;
  return { query, repo, prs };
}
export default async function InvestigationsPage(props: {
  searchParams: Promise<Query>;
}) {
  const result = await attempt(() => loadInvestigations(props));
  if (result.kind === "error") return resultFailure(result.error);
  const { query, repo, prs } = result.value;
  return (
    <>
      <PageHeading
        eyebrow="WORKSPACE / INVESTIGATIONS"
        title="Follow the evidence."
        description="Open a pull request to compare recorded effects, inspect raw measurements, and assess the limits."
      />
      <div className="callout">
        <strong>An observed slowdown is a starting point.</strong>
        <p>
          Investigate the observed change alongside stored fork-bootstrap intervals,
          sample adequacy and practical thresholds when available. A large effect
          is not a regression verdict; timing alone does not identify a root cause.
        </p>
      </div>
      <Panel title="Pull request investigations" description={repo?.fullName}>
        {prs ? (
          <>
            <PrTable prs={prs.items} />
            <Pagination
              data={prs}
              path="/investigations"
              query={query}
              label="pull requests"
            />
          </>
        ) : (
          <EmptyState title="No investigations yet">
            Import runs for the allowlisted repository. Missing results do not
            mean a PR is free of regressions.
          </EmptyState>
        )}
      </Panel>
    </>
  );
}
