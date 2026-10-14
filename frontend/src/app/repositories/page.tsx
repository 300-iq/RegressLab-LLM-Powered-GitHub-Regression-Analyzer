import Link from "next/link";
import { PageHeading } from "@/components/page-heading";
import {
  EmptyState,
  GithubLink,
  OriginBadge,
  Panel,
  resultFailure,
} from "@/components/results/ui";
import { attempt, getRepositories } from "@/lib/results";
async function loadRepositories() {
  const repos = await getRepositories();
  return { repos };
}
export default async function RepositoriesPage() {
  const result = await attempt(() => loadRepositories());
  if (result.kind === "error") return resultFailure(result.error);
  const { repos } = result.value;
  return (
    <>
      <PageHeading
        eyebrow="WORKSPACE / REPOSITORIES"
        title="Repository coverage"
        description="Inspect the benchmark history and commit snapshots of each monitored repository."
      />
      <Panel
        title="Monitored repositories"
        description="One allowlisted Java/JMH repository in the current MVP."
      >
        {repos.length ? (
          repos.map((repo) => (
            <article key={repo.id} className="repository-card">
              <span className="repository-mark" aria-hidden="true">
                ⌘
              </span>
              <div>
                <Link className="row-link" href={`/repositories/${repo.id}`}>
                  {repo.fullName}
                </Link>
                <p>
                  <GithubLink url={repo.githubUrl} origin={repo.dataOrigin} provenance={repo.provenance}>
                    GitHub repository
                  </GithubLink>
                </p>
                <OriginBadge origin={repo.dataOrigin} provenance={repo.provenance} />
              </div>
              <Link
                className="button button-secondary"
                href={`/repositories/${repo.id}`}
              >
                Explore repository →
              </Link>
            </article>
          ))
        ) : (
          <EmptyState title="No repository connected">
            Import measurements for the allowlisted repository to populate this
            workspace. No synthetic records are automatically created.
          </EmptyState>
        )}
      </Panel>
    </>
  );
}
