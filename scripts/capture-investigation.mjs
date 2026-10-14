/** Read-only capture of the built application with its actual connected API. */
import { chromium, devices } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const baseURL = process.env.CAPTURE_URL ?? 'http://127.0.0.1:3000';
const apiURL = process.env.REGRESSLAB_API_URL ?? 'http://127.0.0.1:8080';
const output = resolve(process.env.CAPTURE_OUTPUT ?? 'captures/investigation-workspace');
await mkdir(output, {recursive: true});
async function read(path) {
  const response = await fetch(`${apiURL}/api/v1${path}`, {signal: AbortSignal.timeout(5000)});
  if (!response.ok) throw new Error(`API read failed (${response.status}): ${path}`);
  return response.json();
}
const repos = await read('/repositories');
const repo = repos.find(r => r.dataOrigin === 'REAL');
if (!repo) throw new Error('Capture requires real imported records; no fixtures are substituted.');
const prs = await read(`/repositories/${repo.id}/pull-requests?origin=REAL`);
if (!prs.items.length) throw new Error('No imported PR is available.');
const pr = prs.items[0];
const route = `/investigations/${pr.id}`;
const viewport = {width:1600,height:1050};
const browser = await chromium.launch(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? {executablePath:process.env.PLAYWRIGHT_EXECUTABLE_PATH} : {});
const errors = [];
function track(page) { page.on('pageerror', error => errors.push(error.message)); }
async function ready(page) {
  await page.goto(`${baseURL}${route}`);
  await page.getByRole('heading',{name:pr.title,exact:true}).waitFor();
  await page.locator('.inv-arm svg').first().waitFor();
}
try {
  // Still captures can temporarily resize a viewport; keep them out of the video.
  const desktop = await browser.newContext({viewport,reducedMotion:'reduce'});
  const still = await desktop.newPage(); track(still); await ready(still);
  await still.screenshot({path:resolve(output,'desktop.png')});
  await still.screenshot({path:resolve(output,'desktop-full.png'),fullPage:true});
  await still.getByRole('button',{name:'Fork samples',exact:true}).click();
  await still.locator('.inv-measurement-section').screenshot({path:resolve(output,'measurements.png')});
  await still.locator('.inv-findings').screenshot({path:resolve(output,'what-changed.png')});
  await desktop.close();
  const mobile = await browser.newContext({...devices['iPhone 13'],reducedMotion:'reduce'});
  const phone = await mobile.newPage(); track(phone); await ready(phone);
  await phone.screenshot({path:resolve(output,'mobile.png')});
  await phone.screenshot({path:resolve(output,'mobile-full.png'),fullPage:true});
  const mobileNoOverflow = await phone.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  await mobile.close();

  const recording = await browser.newContext({viewport,reducedMotion:'reduce',recordVideo:{dir:output,size:viewport}});
  const page = await recording.newPage(); track(page); const video = page.video();
  await ready(page);
  // Pauses are for a readable recording, after explicit readiness checks.
  await page.waitForTimeout(2500);
  await page.locator('#inv-measurements').scrollIntoViewIfNeeded();
  await page.getByRole('button',{name:'Fork samples',exact:true}).click();
  await page.waitForTimeout(2500);
  await page.getByRole('heading',{name:'What changed?',exact:true}).scrollIntoViewIfNeeded();
  await page.waitForTimeout(2500);
  await page.getByRole('button',{name:'Candidate',exact:true}).click();
  await page.getByLabel('JVM fork',{exact:true}).selectOption('1');
  await page.waitForTimeout(2500);
  await page.locator('#inv-conditions').scrollIntoViewIfNeeded();
  await page.waitForTimeout(2000);
  await page.locator('#inv-history').scrollIntoViewIfNeeded();
  await page.waitForTimeout(2500);
  await page.locator('a.inv-case:not([aria-current="true"])').first().click();
  await page.locator('.inv-arm svg').first().waitFor();
  await page.evaluate(() => window.scrollTo(0,0));
  await page.waitForTimeout(2500);
  await recording.close();
  await video.saveAs(resolve(output,'investigation-demo.webm')); await video.delete();
  if (!mobileNoOverflow || errors.length) throw new Error(`Capture encountered layout/runtime errors: ${JSON.stringify({mobileNoOverflow,errors})}`);
  await writeFile(resolve(output,'capture.json'),JSON.stringify({capturedAt:new Date().toISOString(),baseURL,apiURL,repository:repo.fullName,pullRequest:pr.githubUrl,route,dataOrigin:repo.dataOrigin,desktop:viewport,mobile:'iPhone 13',runtimeErrors:errors,mobileNoOverflow},null,2)+'\n');
  console.log(`Screenshots and read-only demo recording saved to ${output}`);
} finally { await browser.close(); }
