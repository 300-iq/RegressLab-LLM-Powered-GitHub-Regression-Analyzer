import { expect, test, type Page } from "@playwright/test";
const api = process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080";
async function storedContext(page: Page) {
  const response = await page.request.get(`${api}/api/v1/repositories`);
  expect(response.ok()).toBe(true);
  const repositories = await response.json();
  const repo = repositories[0];
  if (!repo) return null;
  const prsResponse = await page.request.get(
    `${api}/api/v1/repositories/${repo.id}/pull-requests?origin=${repo.dataOrigin}`,
  );
  expect(prsResponse.ok()).toBe(true);
  const prs = await prsResponse.json();
  if (!prs.items.length) return null;
  const detailResponse = await page.request.get(
    `${api}/api/v1/pull-requests/${prs.items[0].id}`,
  );
  expect(detailResponse.ok()).toBe(true);
  return { repo, detail: await detailResponse.json() };
}
async function noOverflow(page: Page) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
}

test("repository case filters use real history with responsive tables and charts", async ({
  page,
}) => {
  if (process.env.E2E_REQUIRE_API !== "true") {
    await page.goto("/repositories");
    await expect(
      page
        .getByRole("heading", { name: "Repository coverage" })
        .or(
          page.getByRole("heading", {
            name: "Measurements could not be loaded",
          }),
        ),
    ).toBeVisible();
    await noOverflow(page);
    return;
  }
  const context = await storedContext(page);
  if (!context) {
    await page.goto("/repositories");
    await expect(
      page.getByRole("heading", { name: "No repository connected" }),
    ).toBeVisible();
    return;
  }
  await page.goto(`/repositories/${context.repo.id}`);
  await expect(
    page.getByRole("heading", { name: context.repo.fullName, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Repositories", exact: true }).first(),
  ).toHaveAttribute("aria-current", "page");
  const filter = page.getByLabel("Benchmark case", { exact: true });
  const options = await filter.locator("option").all();
  expect(options.length).toBeGreaterThan(1);
  const value = await options.at(-1)!.getAttribute("value");
  await filter.selectOption(value!);
  await page.getByRole("button", { name: "Apply filter" }).click();
  await expect(page).toHaveURL(new RegExp(`case=${value}`));
  await expect(filter).toHaveValue(value!);
  const historyResponse = await page.request.get(
    `${api}/api/v1/cases/${value}/history?origin=${context.repo.dataOrigin}&page=0&size=20`,
  );
  expect(historyResponse.ok()).toBe(true);
  const history = await historyResponse.json();
  const conditions = new Set(
    history.items.map((point: { run: { environmentSha256: string }; configurationSha256: string }) =>
      `${point.run.environmentSha256}/${point.configurationSha256}`,
    ),
  );
  expect(conditions.size).toBeGreaterThan(0);
  const historyTables = page.getByRole("table", {
    name: "Historical fork means and comparison conditions",
  });
  await expect(historyTables).toHaveCount(conditions.size);
  for (const table of await historyTables.all()) await expect(table).toBeVisible();
  if (conditions.size > 1)
    await expect(page.getByText(`${conditions.size} condition groups; incompatible environments/configurations are shown separately.`)).toBeVisible();
  await expect(
    page.getByText(/Lower is better|Higher is better/).first(),
  ).toBeVisible();
  await expect(page.locator(".recharts-surface").first()).toBeVisible();
  await noOverflow(page);
  if (context.repo.dataOrigin === "DEMO")
    await expect(
      page.locator("main a[href^='https://github.com/']"),
    ).toHaveCount(0);
});

test("PR investigation exposes effect limits, exact observations, and retained versions", async ({
  page,
}) => {
  if (process.env.E2E_REQUIRE_API !== "true") {
    await page.goto("/investigations");
    await expect(
      page
        .getByRole("heading", { name: "Follow the evidence." })
        .or(
          page.getByRole("heading", {
            name: "Measurements could not be loaded",
          }),
        ),
    ).toBeVisible();
    await noOverflow(page);
    return;
  }
  const context = await storedContext(page);
  if (!context) {
    await page.goto("/investigations");
    await expect(
      page.getByRole("heading", { name: "No investigations yet" }),
    ).toBeVisible();
    return;
  }
  const { detail } = context;
  const analysis = detail.comparisons.items[0];
  expect(analysis).toBeTruthy();
  await page.goto(
    `/investigations/${detail.pullRequest.id}?comparison=${analysis.id}`,
  );
  await expect(
    page.getByRole("heading", { name: detail.pullRequest.title, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("01 / DID PERFORMANCE CHANGE?", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("02 / HOW TRUSTWORTHY IS IT?", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("03 / WHAT SUPPORTS THE OBSERVATION?", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".inv-effect").first()).toBeInViewport();
  const snapshotsResponse = await page.request.get(`${api}/api/v1/comparisons/${analysis.id}/analyses`);
  expect(snapshotsResponse.ok()).toBe(true);
  const snapshots = await snapshotsResponse.json();
  const currentAnalysis = snapshots.items.find((item: {analysisVersion: string}) => item.analysisVersion === "fork-bootstrap-v2");
  await expect(page.locator(".inv-summary-card").nth(1)).toContainText(currentAnalysis ? "Inconclusive" : "Descriptive only");
  if (currentAnalysis) {
    await expect(page.getByRole("heading", {name:"What changed?"})).toBeVisible();
    await expect(page.getByRole("heading", {name:"Too few forks for a confident classification"})).toBeVisible();
    await expect(page.locator(".inv-verdict")).toContainText(currentAnalysis.result.explanation);
    await expect(page.locator(".inv-verdict")).toContainText("Coverage is not guaranteed");
  }
  if(process.env.E2E_EXPECT_REAL === "true")
    await page.screenshot({path:test.info().outputPath("real-summary.png")});
  await expect(
    page.getByRole("button", { name: "Candidate", exact: true }),
  ).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "Candidate", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Candidate", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  const sampleResponse = await page.request.get(
    `${api}/api/v1/runs/${analysis.candidateRunId}/cases/${analysis.benchmarkCaseId}/samples`,
  );
  expect(sampleResponse.ok()).toBe(true);
  const samples = await sampleResponse.json();
  const rawTable = page.getByRole("table", {
    name: `Candidate raw iterations in ${samples.unit}`,
  });
  await expect(rawTable).toBeVisible();
  await expect(rawTable.locator("tbody tr").first()).toContainText(
    String(samples.samples.items[0].value),
  );
  await expect(
    page.getByText(detail.pullRequest.baselineSha, { exact: true }).first(),
  ).toBeVisible();
  if(process.env.E2E_EXPECT_REAL === "true") {
    expect(context.repo.dataOrigin).toBe("REAL");
    expect(context.repo.provenance).toBe("GITHUB_METADATA");
    await expect(page.getByRole("link",{name:/GitHub pull request/})).toHaveAttribute("href",detail.pullRequest.githubUrl);
    const baselineResponse=await page.request.get(`${api}/api/v1/runs/${analysis.baselineRunId}`);
    expect(baselineResponse.ok()).toBe(true);
    const baselineDetail=await baselineResponse.json();
    await page.getByText("GitHub execution evidence and alternating order", {exact:true}).click();
    await expect(page.getByRole("link",{name:/Open workflow run/})).toHaveAttribute("href",baselineDetail.run.githubUrl);
    await expect(page.getByRole("table",{name:"Reported GitHub benchmark execution order"})).toBeVisible();
    await expect(page.getByRole("table",{name:"Reported GitHub benchmark execution order"}).locator("tbody tr")).toHaveCount(6);
    await expect(page.locator(".inv-summary-card").nth(1)).toContainText("measurement claims remain untrusted");
    await page.screenshot({path:test.info().outputPath("real-investigation.png"),fullPage:true});
  }
  const older = snapshots.items.find((item: {analysisVersion: string}) => item.analysisVersion === "fork-bootstrap-v1");
  if (older) {
    await page.getByLabel("Stored analysis policy").selectOption(older.id);
    await page.getByRole("button", {name:"Inspect policy"}).click();
    await expect(page).toHaveURL(new RegExp(`analysis=${older.id}`));
    await expect(page.getByText("Preserved v1 analysis", {exact:true})).toBeVisible();
  }
  await noOverflow(page);
});

test("benchmark detail filters conditions and run-only inspection does not invent a comparison", async ({
  page,
}) => {
  if (process.env.E2E_REQUIRE_API !== "true") {
    await page.goto("/trends");
    await expect(
      page
        .getByRole("heading", { name: "Performance, over time." })
        .or(
          page.getByRole("heading", {
            name: "Measurements could not be loaded",
          }),
        ),
    ).toBeVisible();
    await noOverflow(page);
    return;
  }
  const context = await storedContext(page);
  if (!context) {
    await page.goto("/trends");
    await expect(
      page.getByRole("heading", { name: "No history available" }),
    ).toBeVisible();
    return;
  }
  const run = context.detail.runs.items[0];
  const runResponse = await page.request.get(`${api}/api/v1/runs/${run.id}`);
  expect(runResponse.ok()).toBe(true);
  const detail = await runResponse.json(),
    benchmark = detail.benchmarks[0].benchmarkCase;
  await page.goto(`/benchmarks/${benchmark.id}?run=${run.id}`);
  await expect(
    page
      .getByRole("heading", { name: benchmark.identifier, exact: true })
      .first(),
  ).toBeVisible();
  await page.getByLabel("Test conditions").selectOption("matching");
  await page.getByRole("button", { name: "Apply filter" }).click();
  await expect(page).toHaveURL(/conditions=matching/);
  await expect(
    page.getByRole("heading", { name: "Repeat measurement variability" }),
  ).toBeVisible();
  await expect(
    page.getByRole("table", {
      name: "Repeat measurement variability by JVM fork",
    }),
  ).toBeVisible();
  await noOverflow(page);
  await page.goto(
    `/investigations/${context.detail.pullRequest.id}?run=${run.id}`,
  );
  await expect(
    page.getByText("No stored analysis selected.", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Run measurements" }),
  ).toBeVisible();
  await expect(
    page.getByLabel("Benchmark case", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".answer-grid")).toHaveCount(0);
  await noOverflow(page);
});

test("invalid result identifiers render a no-index not-found state", async ({
  page,
}) => {
  const response = await page.goto("/investigations/not-a-uuid");
  // Next.js returns 200 once a loading boundary has streamed; notFound() still
  // supplies the not-found UI and noindex. Non-streamed missing routes return 404.
  expect([200, 404]).toContain(response?.status());
  await expect(page.locator('meta[name="robots"]').first()).toHaveAttribute(
    "content",
    /noindex/,
  );
  await expect(
    page.getByRole("heading", { name: "Page not found" }),
  ).toBeVisible();
  await noOverflow(page);
});

test("investigation workspace explores actual cases, forks and historical run pairs by keyboard", async ({page}) => {
  test.skip(process.env.E2E_REQUIRE_API !== "true" || process.env.E2E_EXPECT_REAL !== "true", "Accepted real GitHub dataset required");
  const context = await storedContext(page);
  test.skip(!context, "No persisted PR");
  const {detail, repo} = context!;
  await page.goto(`/investigations/${detail.pullRequest.id}`);
  const options = page.getByRole("link", {name:/^Inspect /}).filter({has:page.locator("small")});
  await expect(options).toHaveCount(2);
  const otherCase = page.locator("a.inv-case:not([aria-current=\"true\"])").first();
  const targetName = (await otherCase.getAttribute("aria-label"))!;
  await otherCase.focus(); await page.keyboard.press("Enter");
  await expect(page.getByRole("link",{name:targetName,exact:true})).toHaveAttribute("aria-current","true");
  await page.getByRole("button",{name:"Fork samples",exact:true}).click();
  await expect(page.getByRole("button",{name:"Fork samples",exact:true})).toHaveAttribute("aria-pressed","true");
  await expect(page.locator(".inv-dot")).toHaveCount(30);
  await page.getByRole("button",{name:"Candidate",exact:true}).click();
  await page.getByLabel("JVM fork",{exact:true}).selectOption("1");
  const table = page.getByRole("table",{name:/Candidate raw iterations/});
  await expect(table.locator("tbody tr")).toHaveCount(5);
  for(const row of await table.locator("tbody tr").all()) await expect(row.locator("td").first()).toHaveText("1");
  await expect(page.getByText("Recorded conditions match",{exact:true})).toBeVisible();

  await expect(page.locator(".inv-history-group tbody tr")).toHaveCount(4);
  await expect(page.locator(".inv-history-group td").getByText(/Current PR snapshot/)).toHaveCount(2);
  await expect(page.locator(".inv-history-group td").getByText(/Historical snapshot/)).toHaveCount(2);
  if(repo.dataOrigin === "REAL") {
    await expect(page.locator(".inv-history-plot a")).toHaveCount(4);
    await expect(page.locator(".inv-commit.baseline a")).toHaveAttribute("href",`${repo.githubUrl}/commit/${detail.pullRequest.baselineSha}`);
  }
  const pairs = page.getByLabel("Compared runs",{exact:true});
  const pairOptions = await pairs.locator("option").all();
  if(pairOptions.length > 1) {
    await pairs.selectOption((await pairOptions.at(-1)!.getAttribute("value"))!);
    await page.getByRole("button",{name:"Inspect runs",exact:true}).click();
    await expect(page.getByText("Earlier commit snapshot",{exact:true})).toBeVisible();
    await expect(page.locator(".inv-summary-card").nth(1)).toContainText("Descriptive only");
    await expect(page.locator(".inv-analysis-missing").getByText("No current bootstrap analysis stored",{exact:true})).toBeVisible();
  }
  await noOverflow(page);
});

test("switching cases preserves the selected historical pair in the run selector", async ({page}) => {
  test.skip(process.env.E2E_REQUIRE_API !== "true" || process.env.E2E_EXPECT_REAL !== "true", "Accepted real records required");
  const context = await storedContext(page);
  expect(context).toBeTruthy();
  const {detail} = context!;
  const earlier = detail.comparisons.items.at(-1)!;
  const other = detail.comparisons.items.find((c: {baselineRunId:string;candidateRunId:string;benchmarkCaseId:string}) =>
    c.baselineRunId === earlier.baselineRunId && c.candidateRunId === earlier.candidateRunId && c.benchmarkCaseId !== earlier.benchmarkCaseId);
  expect(other).toBeTruthy();
  await page.goto(`/investigations/${detail.pullRequest.id}?comparison=${earlier.id}`);
  await expect(page.getByLabel("Compared runs",{exact:true})).toHaveValue(earlier.id);
  await page.locator('a.inv-case:not([aria-current="true"])').first().click();
  await expect(page).toHaveURL(new RegExp(`comparison=${other.id}`));
  await expect(page.getByText("Earlier commit snapshot",{exact:true})).toBeVisible();
  await expect(page.getByLabel("Compared runs",{exact:true})).toHaveValue(other.id);
});

test("canonical and uppercase PR identifiers display the same persisted investigation", async ({page}) => {
  test.skip(process.env.E2E_REQUIRE_API !== "true" || process.env.E2E_EXPECT_REAL !== "true", "Accepted real records required");
  const context = await storedContext(page);
  expect(context).toBeTruthy();
  const pr = context!.detail.pullRequest;
  const direct = await page.request.get(`${api}/api/v1/pull-requests/${pr.id.toUpperCase()}`);
  expect(direct.ok()).toBe(true);
  await page.goto(`/investigations/${pr.id.toUpperCase()}`);
  await expect(page.getByRole("heading",{name:pr.title,exact:true})).toBeVisible();
  await expect(page.locator(".inv-classification")).toHaveText("Inconclusive");
  await noOverflow(page);
});
