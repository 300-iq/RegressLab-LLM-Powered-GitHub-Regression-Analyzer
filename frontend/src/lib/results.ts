import { cache } from "react";
import { z } from "zod";
import {
  analysisSchema,
  comparisonSchema,
  historySchema,
  pageSchema,
  prDetailSchema,
  prSchema,
  repositorySchema,
  runDetailSchema,
  runSchema,
  samplePageSchema,
  type Repository,
} from "./contracts";

export class ApiError extends Error {
  constructor(public readonly kind: "missing" | "unavailable" | "invalid") {
    super(kind);
  }
}
export async function readApi<T>(
  path: string,
  schema: z.ZodType<T>,
): Promise<T> {
  try {
    const response = await fetch(
      `${process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080"}/api/v1${path}`,
      {
        cache: "no-store",
        signal: AbortSignal.timeout(5000),
      },
    );
    if (!response.ok)
      throw new ApiError(response.status === 404 ? "missing" : "unavailable");
    const parsed = schema.safeParse(await response.json());
    if (!parsed.success) throw new ApiError("invalid");
    return parsed.data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError("unavailable");
  }
}
export function requireId(value: string) {
  if (!z.uuid().safeParse(value).success) throw new ApiError("missing");
  return value;
}
export type Query = Record<string, string | string[] | undefined>;
export function queryValue(query: Query, key: string) {
  const value = query[key];
  return typeof value === "string" ? value : undefined;
}
export function pageNumber(query: Query, key = "page") {
  const value = queryValue(query, key) ?? "0";
  return /^\d{1,6}$/.test(value) && Number(value) <= 100000 ? Number(value) : 0;
}
export function queryHref(
  path: string,
  query: Query,
  updates: Record<string, string | number | undefined>,
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query))
    if (typeof value === "string") params.set(key, value);
  for (const [key, value] of Object.entries(updates)) {
    if (value === undefined) params.delete(key);
    else params.set(key, String(value));
  }
  return `${path}${params.size ? `?${params}` : ""}`;
}
export const getRepositories = cache(() =>
  readApi("/repositories", z.array(repositorySchema)),
);
export async function getRepository(id: string) {
  requireId(id);
  const repository = (await getRepositories()).find((repo) => repo.id === id);
  if (!repository) throw new ApiError("missing");
  return repository;
}
export const getPr = (id: string, page = 0) =>
  readApi(
    `/pull-requests/${requireId(id)}?page=${page}&size=20`,
    prDetailSchema,
  );
export const getPrComparisons = (id: string, page = 0) =>
  readApi(
    `/pull-requests/${requireId(id)}/comparisons?page=${page}&size=20`,
    pageSchema(comparisonSchema),
  );
export const getRun = cache((id: string) =>
  readApi(`/runs/${requireId(id)}`, runDetailSchema),
);
export const getComparison = (id: string) =>
  readApi(`/comparisons/${requireId(id)}`, comparisonSchema);
export const getAnalyses = (comparisonId: string, page = 0) =>
  readApi(`/comparisons/${requireId(comparisonId)}/analyses?page=${page}&size=20`, pageSchema(analysisSchema));
export const getAnalysis = (id: string) =>
  readApi(`/comparison-analyses/${requireId(id)}`, analysisSchema);
export const getPrs = (repo: Repository, page = 0, size = 20) =>
  readApi(
    `/repositories/${repo.id}/pull-requests?origin=${repo.dataOrigin}&page=${page}&size=${size}`,
    pageSchema(prSchema),
  );
export const getRuns = (repo: Repository, page = 0, size = 20) =>
  readApi(
    `/repositories/${repo.id}/runs?origin=${repo.dataOrigin}&page=${page}&size=${size}`,
    pageSchema(runSchema),
  );
export function getHistory(
  caseId: string,
  repo: Repository,
  page = 0,
  environment?: string,
  configuration?: string,
) {
  const query = new URLSearchParams({
    origin: repo.dataOrigin,
    page: String(page),
    size: "20",
  });
  if (environment) query.set("environmentSha256", environment);
  if (configuration) query.set("configurationSha256", configuration);
  return readApi(
    `/cases/${requireId(caseId)}/history?${query}`,
    pageSchema(historySchema),
  );
}
export const getSamples = (runId: string, caseId: string, page = 0) =>
  readApi(
    `/runs/${requireId(runId)}/cases/${requireId(caseId)}/samples?page=${page}&size=100`,
    samplePageSchema,
  );
// The producer contract bounds a case to 300 observations. Charts use every page,
// never a truncated first page. Sample-page metadata must match the requested case.
export async function getAllSamples(runId: string, caseId: string) {
  const first = await getSamples(runId, caseId);
  if (
    first.runId !== runId ||
    first.benchmarkCaseId !== caseId ||
    first.samples.page !== 0 ||
    first.samples.totalElements > 300
  )
    throw new ApiError("invalid");
  const rest = await Promise.all(
    Array.from(
      {
        length: Math.ceil(first.samples.totalElements / first.samples.size) - 1,
      },
      (_, i) => getSamples(runId, caseId, i + 1),
    ),
  );
  if (
    rest.some(
      (page, i) =>
        page.runId !== runId ||
        page.benchmarkCaseId !== caseId ||
        page.unit !== first.unit ||
        page.dataOrigin !== first.dataOrigin ||
        page.samples.totalElements !== first.samples.totalElements ||
        page.samples.page !== i + 1 ||
        page.samples.size !== first.samples.size,
    )
  )
    throw new ApiError("invalid");
  const samples = [first, ...rest].flatMap((page) => page.samples.items);
  if (
    samples.length !== first.samples.totalElements ||
    new Set(samples.map((s) => `${s.forkIndex}:${s.iterationIndex}`)).size !==
      samples.length
  )
    throw new ApiError("invalid");
  return { ...first, values: samples };
}
// Catch network/contract failures while loading data. Rendering failures are handled
// separately by Next's error boundary, rather than a try/catch around JSX.
export async function attempt<T>(
  load: () => Promise<T>,
): Promise<{ kind: "ready"; value: T } | { kind: "error"; error: unknown }> {
  try {
    return { kind: "ready", value: await load() };
  } catch (error) {
    return { kind: "error", error };
  }
}

export const getGithubAudit = cache(async (id: string) => {
  const { githubAuditSchema } = await import("./contracts");
  return readApi(`/runs/${requireId(id)}/github-audit`, githubAuditSchema);
});
