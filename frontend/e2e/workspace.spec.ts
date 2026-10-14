import { expect, test } from "@playwright/test";

// Exercise the real built application. No route interception or fabricated API.
test("overview renders with truthful service and empty-result states", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "A clearer view of every change." }),
  ).toBeVisible();
  if (process.env.E2E_EXPECT_REAL === "true") {
    await expect(page.getByText("Public demonstration workload", {exact:true})).toBeVisible();
    await expect(page.getByText("DEMO · Development fixtures", {exact:true})).toHaveCount(0);
  } else if (process.env.E2E_EXPECT_DEMO === "true") {
    await expect(
      page.getByText("DEMO · Development fixtures", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "These persisted records and measurements are synthetic. GitHub provenance is unverified.",
      ),
    ).toBeVisible();
  } else {
    if (process.env.E2E_REQUIRE_API === "true")
      await expect(
        page.getByText(/No benchmark results are seeded or simulated/),
      ).toBeVisible();
  }
  await expect(
    page.getByRole("link", { name: "Overview", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  if (process.env.E2E_REQUIRE_API === "true") {
    if ([process.env.E2E_EXPECT_DEMO, process.env.E2E_EXPECT_REAL].includes("true"))
      await expect(
        page.getByRole("heading", { name: "Recent benchmark runs" }),
      ).toBeVisible();
    else
      await expect(
        page.getByRole("heading", {
          name: "Your first investigation starts here",
        }),
      ).toBeVisible();
    await expect(
      page.getByText("API unavailable", { exact: true }),
    ).toHaveCount(0);
    if ([process.env.E2E_EXPECT_DEMO, process.env.E2E_EXPECT_REAL].includes("true")) {
      // Valid analysis upgrades retain older versions. Verify the rendered count
      // against the actual API, while keeping the fixture's raw-data invariants.
      const response = await page.request.get(
        `${process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080"}/api/v1/overview`,
      );
      expect(response.ok()).toBe(true);
      const overview = await response.json();
      if(process.env.E2E_EXPECT_DEMO === "true")
        expect(overview).toMatchObject({repositories:1,pullRequests:1,observations:60});
      else {
        expect(overview.repositories).toBe(1);
        expect(overview.pullRequests).toBeGreaterThan(0);
        expect(overview.observations).toBeGreaterThan(0);
      }
      expect(overview.comparisons).toBeGreaterThanOrEqual(2);
      await expect(page.locator(".metric-value")).toHaveText([
        overview.repositories.toLocaleString("en-US"),
        overview.pullRequests.toLocaleString("en-US"),
        overview.comparisons.toLocaleString("en-US"),
        overview.observations.toLocaleString("en-US"),
      ]);
    } else {
      await expect(page.locator(".metric-value")).toHaveText([
        "0",
        "0",
        "0",
        "0",
      ]);
    }
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("all workspace routes work and active navigation follows the page", async ({
  page,
}) => {
  await page.goto("/");
  for (const [label, heading] of [
    ["Repositories", "Repository coverage"],
    ["Investigations", "Follow the evidence."],
    ["Historical trends", "Performance, over time."],
    ["Benchmark pipeline", "A traceable benchmark pipeline."],
    ["Methodology", "A number is only the beginning."],
  ]) {
    const link = page.getByRole("link", { name: label, exact: true });
    await link.click();
    const expectedHeading = page.getByRole("heading", {
      name: heading,
      exact: true,
    });
    if (
      process.env.E2E_REQUIRE_API !== "true" &&
      ["Repositories", "Investigations", "Historical trends"].includes(label)
    ) {
      await expect(
        expectedHeading.or(
          page.getByRole("heading", {
            name: "Measurements could not be loaded",
          }),
        ),
      ).toBeVisible();
    } else await expect(expectedHeading).toBeVisible();
    await expect(link).toHaveAttribute("aria-current", "page");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  }
});

test("unknown routes return a usable not-found view", async ({ page }) => {
  const response = await page.goto("/missing-page");
  expect(response?.status()).toBe(404);
  await expect(
    page.getByRole("heading", { name: "Page not found" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Back to overview" }).click();
  await expect(
    page.getByRole("heading", { name: "A clearer view of every change." }),
  ).toBeVisible();
});
