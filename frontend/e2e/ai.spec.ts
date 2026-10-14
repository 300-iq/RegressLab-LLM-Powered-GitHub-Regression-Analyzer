import { expect, test, type Page } from "@playwright/test";
const api=process.env.REGRESSLAB_API_URL ?? "http://127.0.0.1:8080";
async function actualReport(page:Page,eligibleOnly=false) {
  const repos=await (await page.request.get(`${api}/api/v1/repositories`)).json();
  if(!repos.length)return null;
  const prs=await (await page.request.get(`${api}/api/v1/repositories/${repos[0].id}/pull-requests?size=100`)).json();
  const desired=Number(process.env.AI_E2E_PR_NUMBER ?? 1);
  const pr=prs.items.find((p:{number:number})=>p.number===desired);if(!pr)return null;
  const detail=await (await page.request.get(`${api}/api/v1/pull-requests/${pr.id}`)).json();
  for(const c of detail.comparisons.items) {
    const reports=await (await page.request.get(`${api}/api/v1/comparisons/${c.id}/ai-investigations?size=5`)).json();
    const report=reports.items?.find((r:{input:{evidence:{analysis:{result:{relativeChangePercent:number|null};policy:{practicalThresholdPercent:number}}}}})=>!eligibleOnly ||
      r.input.evidence.analysis.result.relativeChangePercent!==null && Math.abs(r.input.evidence.analysis.result.relativeChangePercent)>=r.input.evidence.analysis.policy.practicalThresholdPercent);
    if(report)return {pr,comparison:c,report};
  }
  return null;
}
test("stored AI hypotheses use actual evidence and exact GitHub citations",async({page})=>{
  test.skip(process.env.E2E_EXPECT_REAL!=="true","Requires existing genuine reports; no fixture is loaded");
  const data=await actualReport(page);expect(data).not.toBeNull();if(!data)return;
  await page.goto(`/investigations/${data.pr.id}?comparison=${data.comparison.id}`);
  const panel=page.locator("#ai-investigation");
  await expect(panel.getByText("Investigation complete",{exact:true})).toBeVisible();
  await expect(panel.getByText("Unverified hypotheses",{exact:true})).toBeVisible();
  await expect(panel.getByRole("heading",{name:"Performance change summary"})).toBeVisible();
  await expect(panel.getByText("Review before acting",{exact:true})).toBeVisible();
  const ref=data.report.report.hypotheses[0].references[0];
  const summary=panel.locator(".ai-code-references summary").first();await summary.focus();await expect(summary).toBeFocused();await summary.press("Enter");
  const link=panel.getByRole("link",{name:`Open changed line ${ref.line} on GitHub ↗`}).first();
  const file=data.report.input.diff.files.find((f:{filePath:string})=>f.filePath===ref.filePath);
  const sha=ref.side==="BASELINE"?data.report.input.diff.baselineSha:data.report.input.diff.candidateSha;
  const path=ref.side==="BASELINE"?file.previousFilePath:file.filePath;
  await expect(link).toHaveAttribute("href",`https://github.com/${data.report.input.diff.repository}/blob/${sha}/${path.split("/").map(encodeURIComponent).join("/")}#L${ref.line}`);
  await expect(panel.locator(".ai-experiment").first()).toContainText("not executed");
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test("local generation bridge rejects cross-origin and forwarded requests",async({request})=>{
  const variations:Record<string,string>[]=[
    {origin:"https://evil.example","sec-fetch-site":"cross-site"},
    {origin:`http://127.0.0.1:${process.env.E2E_PORT??3000}`,"sec-fetch-site":"same-origin","x-forwarded-for":"127.0.0.1"},
  ];
  for(const headers of variations){
    const r=await request.post("/api/ai-investigations",{headers,data:{comparisonId:"00000000-0000-0000-0000-000000000001",analysisId:"00000000-0000-0000-0000-000000000001"}});
    expect(r.status()).toBe(403);expect((await r.json()).code).toBe("LOCAL_UI_DISABLED");
  }
});
test("Investigate with AI invokes the real backend and preserves the actual outcome",async({page},info)=>{
  test.skip(process.env.E2E_AI_GENERATION!=="true"||info.project.name!=="desktop","Explicit local model generation only; no mocks or automatic retries");
  test.setTimeout(200000);
  const data=await actualReport(page,true);expect(data).not.toBeNull();if(!data)return;
  await page.goto(`/investigations/${data.pr.id}?comparison=${data.comparison.id}&analysis=${data.report.input.evidence.analysis.id}`);
  const panel=page.locator("#ai-investigation"),button=panel.getByRole("button",{name:"Investigate with AI"});
  await expect(button).toBeEnabled();await button.focus();await expect(button).toBeFocused();
  const pending=page.waitForResponse(r=>r.url().endsWith("/api/ai-investigations")&&r.request().method()==="POST",{timeout:180000});
  await button.press("Enter");await expect(panel.getByRole("button",{name:"Generating report…"})).toBeDisabled();
  const response=await pending,result=await response.json();
  if(response.status()===202){
    expect(result.job.id).toBeTruthy();
    await expect(panel.getByText(/^(Investigation complete|Ollama unavailable|Failed analysis)$/)).toBeVisible({timeout:180000});
    const job=await (await page.request.get(`${api}/api/v1/ai-investigation-jobs/${result.job.id}`)).json();
    if(job.status==="SUCCEEDED") {
      const persisted=await (await page.request.get(`${api}/api/v1/ai-investigations/${job.reportId}`)).json();
      await expect(panel.locator(".ai-audit")).toContainText(persisted.id);
      await page.reload();await expect(panel.getByText("Investigation complete",{exact:true})).toBeVisible();
    } else {expect(job.status).toBe("FAILED");await expect(panel.getByRole("alert")).toBeVisible();}
    console.log(`Actual async AI job: ${job.id} ${job.status}, reused=${job.reused}, report=${job.reportId??job.errorCode}`);
  }else{
    expect([409,429,502,503,504]).toContain(response.status());await expect(panel.getByRole("alert")).toBeVisible();
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
