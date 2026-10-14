import { z } from "zod";
import { repositorySchema } from "./contracts";

export const overviewSchema = z
  .object({
    repositories: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    pullRequests: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    comparisons: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    observations: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

const repositoriesSchema = z.array(repositorySchema);

export type DashboardData =
  | {
      kind: "ready";
      overview: z.infer<typeof overviewSchema>;
      repositories: z.infer<typeof repositoriesSchema>;
    }
  | { kind: "unavailable"; reason: "connection" | "response" };

export async function loadDashboard(): Promise<DashboardData> {
  const base = process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080";
  try {
    const responses = await Promise.all([
      fetch(`${base}/api/v1/overview`, {
        cache: "no-store",
        signal: AbortSignal.timeout(3000),
      }),
      fetch(`${base}/api/v1/repositories`, {
        cache: "no-store",
        signal: AbortSignal.timeout(3000),
      }),
    ]);
    if (responses.some((response) => !response.ok))
      return { kind: "unavailable", reason: "response" };
    const [overviewJson, repositoriesJson] = await Promise.all(
      responses.map((response) => response.json()),
    );
    const overview = overviewSchema.safeParse(overviewJson);
    const repositories = repositoriesSchema.safeParse(repositoriesJson);
    if (!overview.success || !repositories.success)
      return { kind: "unavailable", reason: "response" };
    return {
      kind: "ready",
      overview: overview.data,
      repositories: repositories.data,
    };
  } catch {
    return { kind: "unavailable", reason: "connection" };
  }
}
