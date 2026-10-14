import { afterEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import {
  getAllSamples,
  pageNumber,
  queryHref,
  readApi,
  requireId,
} from "./results";
import { caseSchema, comparisonSchema, repositorySchema } from "./contracts";
const run = "2fed3f9e-b2ae-4d96-afaa-4dbaa9172006",
  benchmark = "e0465d02-28cb-43ed-8254-aa9bd1f3cbe3";
const page = (index: number, total = 205) => ({
  runId: run,
  benchmarkCaseId: benchmark,
  unit: "ops/s",
  dataOrigin: "DEMO",
  samples: {
    page: index,
    size: 100,
    totalElements: total,
    items: Array.from(
      { length: Math.max(0, Math.min(100, total - index * 100)) },
      (_, i) => ({
        forkIndex: Math.floor((index * 100 + i) / 30),
        iterationIndex: (index * 100 + i) % 30,
        value: 100 + index * 100 + i,
      }),
    ),
  },
});
afterEach(() => vi.unstubAllGlobals());
describe("real API boundary", () => {
  it("reads every sample page without exposing credentials", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json(page(0)))
      .mockResolvedValueOnce(Response.json(page(1)))
      .mockResolvedValueOnce(Response.json(page(2)));
    vi.stubGlobal("fetch", fetcher);
    const result = await getAllSamples(run, benchmark);
    expect(result.values).toHaveLength(205);
    expect(result.values.at(-1)?.value).toBe(304);
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual(
      [0, 1, 2].map(
        (i) =>
          `http://127.0.0.1:8080/api/v1/runs/${run}/cases/${benchmark}/samples?page=${i}&size=100`,
      ),
    );
    expect(fetcher.mock.calls[0][1]).not.toHaveProperty("headers");
  });
  it.each(["unit", "identity", "count", "duplicate", "incomplete"])(
    "rejects inconsistent sample pagination: %s",
    async (mutation) => {
      const second = page(1);
      if (mutation === "unit") second.unit = "ns/op";
      if (mutation === "identity") second.runId = benchmark;
      if (mutation === "count") second.samples.totalElements = 204;
      if (mutation === "duplicate")
        second.samples.items[0] = page(0).samples.items[0];
      if (mutation === "incomplete") second.samples.items.pop();
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValueOnce(Response.json(page(0)))
          .mockResolvedValueOnce(Response.json(second))
          .mockResolvedValueOnce(Response.json(page(2))),
      );
      await expect(getAllSamples(run, benchmark)).rejects.toMatchObject({
        kind: "invalid",
      });
    },
  );
  it("fails closed on malformed data and distinguishes missing records from outages", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ value: "invented" }))
        .mockResolvedValueOnce(new Response(null, { status: 404 }))
        .mockRejectedValueOnce(new Error("private details")),
    );
    await expect(readApi("/test", z.number())).rejects.toMatchObject({
      kind: "invalid",
    });
    await expect(readApi("/test", z.number())).rejects.toMatchObject({
      kind: "missing",
    });
    await expect(readApi("/test", z.number())).rejects.toMatchObject({
      kind: "unavailable",
      message: "unavailable",
    });
  });
  it("rejects unsupported GitHub hosts and invalid metric direction", () => {
    const repo = {
      id: run,
      fullName: "fixture/demo",
      owner: "fixture",
      name: "demo",
      dataOrigin: "DEMO",
      provenance: "LOCAL_UNVERIFIED",
      githubUrl: "https://github.com.evil.test/fixture/demo",
    };
    expect(repositorySchema.safeParse(repo).success).toBe(false);
    expect(
      caseSchema.safeParse({
        id: benchmark,
        repositoryId: run,
        identifier: "fixture.Case",
        metricName: "throughput",
        unit: "ops/s",
        direction: "LOWER",
      }).success,
    ).toBe(false);
    expect(
      comparisonSchema.safeParse({
        summary: null,
        analysisStatus: "DESCRIPTIVE_ONLY",
      }).success,
    ).toBe(false);
  });
  it("bounds page and identifier inputs and safely encodes query values", () => {
    expect(pageNumber({ page: "100001" })).toBe(0);
    expect(pageNumber({ page: "-1" })).toBe(0);
    expect(pageNumber({ page: "2" })).toBe(2);
    expect(() => requireId("../dev/artifacts")).toThrow();
    expect(
      queryHref(
        "/trends",
        { case: "a&origin=REAL", ignored: ["x"] },
        { page: 2 },
      ),
    ).toBe("/trends?case=a%26origin%3DREAL&page=2");
  });
});
