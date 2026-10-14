import { afterEach, describe, expect, it, vi } from "vitest";
import { loadDashboard } from "./api";

const counts = { repositories: 0, pullRequests: 0, comparisons: 0, observations: 0 };
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
describe("database-backed API boundary", () => {
  it("preserves a valid empty database without fabricating measurements", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(counts)).mockResolvedValueOnce(Response.json([]));
    vi.stubGlobal("fetch", fetcher);
    expect(await loadDashboard()).toEqual({ kind: "ready", overview: counts, repositories: [] });
    expect(fetcher.mock.calls[0][1].cache).toBe("no-store");
  });
  it("shows unavailable service instead of substituting zero counts", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network")));
    expect(await loadDashboard()).toEqual({ kind: "unavailable", reason: "connection" });
  });
  it("rejects failed HTTP responses", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("unavailable", { status: 503 })));
    expect(await loadDashboard()).toEqual({ kind: "unavailable", reason: "response" });
  });
  it("rejects invalid counts", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json({ ...counts, observations: -1 })).mockResolvedValueOnce(Response.json([])));
    expect((await loadDashboard()).kind).toBe("unavailable");
  });
  it("rejects untrusted GitHub link destinations", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json(counts)).mockResolvedValueOnce(Response.json([
      { id: "b2038e25-0664-43e6-9c9b-02a2b112cd88", fullName: "fixture/demo", githubUrl: "https://github.com.evil.test/fixture/demo", owner: "fixture", name: "demo", dataOrigin: "DEMO", provenance: "LOCAL_UNVERIFIED" },
    ])));
    expect((await loadDashboard()).kind).toBe("unavailable");
  });
  it("accepts explicitly labeled persisted development repository metadata", async () => {
    const repository = { id: "b2038e25-0664-43e6-9c9b-02a2b112cd88", fullName: "fixture/demo", githubUrl: "https://github.com/fixture/demo", owner: "fixture", name: "demo", dataOrigin: "DEMO", provenance: "LOCAL_UNVERIFIED" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json(counts)).mockResolvedValueOnce(Response.json([repository])));
    expect(await loadDashboard()).toEqual({ kind: "ready", overview: counts, repositories: [repository] });
  });
});
