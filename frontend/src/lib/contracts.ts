import { z } from "zod";

const id = z.uuid();
const count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
const positive = z.number().positive().finite();
const sha = z.string().regex(/^[0-9a-f]{40}$/);
const hash = z.string().regex(/^[0-9a-f]{64}$/);
const date = z.iso.datetime({ offset: true });
const origin = z.enum(["REAL", "DEMO"]);
// GitHub URLs are inert links. Never accept another scheme, credentials, or host.
const github = z.url().refine((value) => {
  const url = new URL(value);
  return (
    url.protocol === "https:" &&
    url.hostname === "github.com" &&
    !url.username &&
    !url.password &&
    !url.port
  );
});
export const provenanceSchema = z.enum(["LOCAL_UNVERIFIED", "GITHUB_METADATA"]);
const provenance = provenanceSchema;
export const repositorySchema = z
  .object({
    id,
    fullName: z
      .string()
      .regex(/^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9_.-]{1,100}$/),
    githubUrl: github,
    owner: z.string(),
    name: z.string(),
    dataOrigin: origin,
    provenance,
  })
  .strict()
  .refine((repo) => repo.githubUrl === `https://github.com/${repo.fullName}`);
export const prSchema = z
  .object({
    id,
    repositoryId: id,
    number: count.positive(),
    title: z.string(),
    githubUrl: github,
    state: z.enum(["OPEN", "CLOSED", "MERGED"]),
    baselineSha: sha,
    candidateSha: sha,
    updatedAt: date,
    dataOrigin: origin,
    provenance,
  })
  .strict();
export const environmentSchema = z
  .object({
    runner: z.string(),
    os: z.string(),
    architecture: z.string(),
    cpu: z.string(),
    javaVersion: z.string(),
    jmhVersion: z.string(),
  })
  .strict();
export const configurationSchema = z
  .object({
    forkCount: count.positive(),
    measurementIterations: count.positive(),
    warmupIterations: count.positive(),
    warmupTimeMs: positive,
    measurementTimeMs: positive,
    jvmArgs: z.array(z.string()),
  })
  .strict();
export const runSchema = z
  .object({
    id,
    repositoryId: id,
    pullRequestId: id,
    workflowRunId: count.positive(),
    runAttempt: count.positive(),
    githubUrl: github,
    commitSha: sha,
    baselineSha: sha,
    candidateSha: sha,
    branch: z.string(),
    measuredAt: date,
    environment: environmentSchema,
    environmentSha256: hash,
    status: z.enum(["COMPLETE", "FAILED"]),
    dataOrigin: origin,
    provenance,
    artifactId: id,
    artifactIdentifier: z.string(),
    artifactSha256: hash,
    importedAt: date,
  })
  .strict();
export const caseSchema = z
  .object({
    id,
    repositoryId: id,
    identifier: z.string(),
    metricName: z.enum(["average_time", "throughput"]),
    unit: z.enum(["ns/op", "ops/s"]),
    direction: z.enum(["LOWER", "HIGHER"]),
  })
  .strict()
  .refine((value) =>
    value.metricName === "average_time"
      ? value.unit === "ns/op" && value.direction === "LOWER"
      : value.unit === "ops/s" && value.direction === "HIGHER",
  );
export const runBenchmarkSchema = z
  .object({
    benchmarkCase: caseSchema,
    parameters: z.record(z.string(), z.string()),
    configuration: configurationSchema,
    configurationSha256: hash,
    sampleCount: count,
  })
  .strict();
export const runDetailSchema = z
  .object({ run: runSchema, benchmarks: z.array(runBenchmarkSchema) })
  .strict();
const summarySchema = z
  .object({
    baselineForks: count.positive(),
    candidateForks: count.positive(),
    baselineSamples: count.positive(),
    candidateSamples: count.positive(),
    baselineMean: positive,
    candidateMean: positive,
    baselineMedian: positive,
    candidateMedian: positive,
    baselineStdDev: z.number().finite().nonnegative(),
    candidateStdDev: z.number().finite().nonnegative(),
    baselineCv: z.number().finite().nonnegative(),
    candidateCv: z.number().finite().nonnegative(),
    candidateBaselineRatio: positive,
    degradationPercent: z.number().finite(),
  })
  .strict();
export const comparisonSchema = z
  .object({
    id,
    repositoryId: id,
    baselineRunId: id,
    candidateRunId: id,
    benchmarkCaseId: id,
    analysisVersion: z.string(),
    analysisStatus: z.enum([
      "INSUFFICIENT_EVIDENCE",
      "DESCRIPTIVE_ONLY",
      "INCOMPATIBLE",
    ]),
    summary: summarySchema.nullable(),
    reasons: z.array(z.string()),
    dataOrigin: origin,
    provenance,
    createdAt: date,
  })
  .strict()
  .refine((value) =>
    value.analysisStatus === "INCOMPATIBLE"
      ? value.summary === null
      : value.summary !== null,
  );
export function pageSchema<T extends z.ZodType>(item: T) {
  return z
    .object({
      items: z.array(item),
      page: count,
      size: count.positive().max(100),
      totalElements: count,
    })
    .strict();
}
export const prDetailSchema = z
  .object({
    pullRequest: prSchema,
    runs: pageSchema(runSchema),
    comparisons: pageSchema(comparisonSchema),
  })
  .strict();
export const sampleSchema = z
  .object({ forkIndex: count, iterationIndex: count, value: positive })
  .strict();
export const samplePageSchema = z
  .object({
    runId: id,
    benchmarkCaseId: id,
    unit: z.enum(["ns/op", "ops/s"]),
    dataOrigin: origin,
    samples: pageSchema(sampleSchema),
  })
  .strict();
export const historySchema = z
  .object({
    run: runSchema,
    parameters: z.record(z.string(), z.string()),
    configuration: configurationSchema,
    configurationSha256: hash,
    forks: count.positive(),
    sampleCount: count,
    mean: positive,
  })
  .strict();
export type Repository = z.infer<typeof repositorySchema>;
export type PullRequest = z.infer<typeof prSchema>;
export type Run = z.infer<typeof runSchema>;
export type BenchmarkCase = z.infer<typeof caseSchema>;
export type RunBenchmark = z.infer<typeof runBenchmarkSchema>;
export type RunDetail = z.infer<typeof runDetailSchema>;
export type Comparison = z.infer<typeof comparisonSchema>;
export type Sample = z.infer<typeof sampleSchema>;
export type HistoryPoint = z.infer<typeof historySchema>;
export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
};

const executionStatus = z.enum(["COMPLETE", "BUILD_FAILED", "BENCHMARK_FAILED", "TIMEOUT", "NOT_RUN"]);
const executionTiming = {
  status: executionStatus,
  exitCode: z.number().int().nullable(),
  startedAt: date.nullable(),
  finishedAt: date.nullable(),
  elapsedMs: count.max(900000),
  error: z.string().max(500).nullable(),
};
export const githubAuditSchema = z.object({
  githubArtifactId: count.positive(),
  archiveSha256: hash,
  verifiedAt: date,
  execution: z.object({
    schemaVersion: z.literal(1),
    strategy: z.literal("alternating-forks-v1"),
    harnessSha256: hash,
    order: z.array(z.enum(["BASELINE", "CANDIDATE"])).length(6),
    builds: z.object({BASELINE:z.object(executionTiming), CANDIDATE:z.object(executionTiming)}),
    blocks: z.array(z.object({
      ...executionTiming,
      arm:z.enum(["BASELINE", "CANDIDATE"]),
      forkIndex:count.max(2),
      observations:z.array(z.object({identifier:z.string(),unit:z.literal("ns/op"),values:z.array(positive).length(5)})).max(2),
    })).length(6),
  }),
  // Public GitHub API evidence is retained by the backend; only the reviewed
  // execution protocol is rendered. No URL or command from evidence is executed.
  verificationEvidence:z.record(z.string(),z.unknown()),
});
export type GithubAudit = z.infer<typeof githubAuditSchema>;

const statisticsSchema = z.object({
  count: count.positive(), mean: positive, median: positive,
  standardDeviation: z.number().finite().nonnegative().nullable(),
  coefficientOfVariation: z.number().finite().nonnegative().nullable(),
}).strict();
const armSummarySchema = z.object({
  unit: z.enum(["ns/op", "ops/s"]), direction: z.enum(["LOWER", "HIGHER"]),
  rawMeasurements: statisticsSchema, forkMeans: statisticsSchema,
  orderedForkMeans: z.array(positive).max(10),
}).strict().refine(arm => arm.orderedForkMeans.length === arm.forkMeans.count && arm.rawMeasurements.count >= arm.forkMeans.count);
export const classificationSchema = z.enum([
  "REGRESSION_INDICATED", "IMPROVEMENT_INDICATED", "NO_MEANINGFUL_CHANGE_DETECTED", "INCONCLUSIVE", "INVALID_COMPARISON",
]);
export const analysisSchema = z.object({
  id, comparisonId: id, analysisVersion: z.enum(["fork-bootstrap-v1", "fork-bootstrap-v2"]),
  policySha256: hash, inputSha256: hash, dataOrigin: origin, provenance, createdAt: date,
  policy: z.object({
    practicalThresholdPercent: z.number().finite().min(.1).max(50), minimumForksPerArm: z.literal(10),
    minimumIterationsPerFork: z.literal(5), minimumDistinctForkMeans: z.literal(5), maximumForkCv: z.literal(.2),
    resamples: z.literal(10000), seed: z.literal(20261013), familyConfidenceLevel: z.literal(.95),
    familySize: count.positive().max(20),
  }).strict(),
  result: z.object({
    benchmarkIdentifier: z.string().nullable(), unit: z.enum(["ns/op", "ops/s"]).nullable(), direction: z.enum(["LOWER", "HIGHER"]).nullable(),
    baseline: armSummarySchema.nullable(), candidate: armSummarySchema.nullable(), relativeChangePercent: z.number().finite().nullable(),
    interval: z.object({
      lowerPercent: z.number().finite(), upperPercent: z.number().finite(), nominalConfidenceLevel: z.number().min(.95).lt(1),
      method: z.literal("independent-arm-fork-percentile-bootstrap"), statistic: z.literal("direction-adjusted-relative-median-of-fork-means-percent"),
      resamplingUnit: z.literal("JVM_FORK_MEAN"), quantileMethod: z.literal("linear-interpolation-type-7"),
      resamples: z.literal(10000), seed: z.literal(20261013), exploratory: z.boolean(),
    }).strict().refine(interval => interval.lowerPercent <= interval.upperPercent).nullable(),
    classification: classificationSchema, explanation: z.string(), reasons: z.array(z.string()),
    environmentWarnings: z.array(z.string()), limitations: z.array(z.string()),
  }).strict(),
}).strict().superRefine((analysis, ctx) => {
  const r = analysis.result, interval = r.interval;
  const invalid = r.classification === "INVALID_COMPARISON";
  for (const arm of [r.baseline, r.candidate]) {
    if (!invalid && arm && (arm.unit !== r.unit || arm.direction !== r.direction))
      ctx.addIssue({ code: "custom", message: "Arm metadata must match the analyzed metric" });
  }
  if (!invalid && (!r.benchmarkIdentifier || !r.unit || !r.direction || !r.baseline || !r.candidate || r.relativeChangePercent === null || interval === null))
    ctx.addIssue({ code: "custom", message: "Non-invalid analyses require complete metric evidence, effect and interval" });
  const indication = !invalid && r.classification !== "INCONCLUSIVE";
  if (invalid && (r.relativeChangePercent !== null || interval !== null))
    ctx.addIssue({ code: "custom", message: "Invalid comparisons cannot carry an effect or interval" });
  if (interval && Math.abs(interval.nominalConfidenceLevel - (1 - (1 - analysis.policy.familyConfidenceLevel) / analysis.policy.familySize)) > 1e-12)
    ctx.addIssue({ code: "custom", message: "Interval confidence does not match the recorded policy" });
  if (indication && (!interval || interval.exploratory || r.relativeChangePercent === null ||
      !r.baseline || !r.candidate || r.baseline.forkMeans.count < analysis.policy.minimumForksPerArm ||
      r.candidate.forkMeans.count < analysis.policy.minimumForksPerArm || analysis.provenance !== "GITHUB_METADATA"))
    ctx.addIssue({ code: "custom", message: "Indications require eligible evidence" });
});
export type Analysis = z.infer<typeof analysisSchema>;
export type Classification = z.infer<typeof classificationSchema>;
