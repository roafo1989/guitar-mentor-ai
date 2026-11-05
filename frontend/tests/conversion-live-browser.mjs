import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createServer } from 'vite';
import { chromium } from 'playwright';

// This harness is launched only by LibraryConversionBrowserPostgresTest against its isolated server.
assert.equal(process.env.GM_TEST_REAL_BROWSER, 'true');
const target = new URL(process.env.GM_TEST_BACKEND_URL);
assert.equal(target.hostname, '127.0.0.1');
const corpus = path.resolve(process.env.GM_TEST_CONVERSION_CORPUS);
const evidence = path.resolve('../artifacts/conversion-170e/browser');
await mkdir(evidence, { recursive: true });
process.env.VITE_GUITAR_MENTOR_AUTH_REQUIRED = 'true';
process.env.VITE_GUITAR_MENTOR_LIGHT_MINIMAL = 'true';
const server = await createServer({ server: { host: '127.0.0.1', port: 0,
  proxy: { '/api': { target: target.origin, changeOrigin: false }, '/logout': { target: target.origin, changeOrigin: false } } } });
let browser, page;
const report = [], pageErrors = [];
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', timeout: 15000 });
// Use a separate tag for fault injection; never remove the normal worker image.
const testImage = 'gm-conversion-worker:170e-browser-' + randomUUID();
let imageRemoved = false;
try {
  await server.listen();
  const base = 'http://127.0.0.1:' + server.httpServer.address().port;
  browser = await chromium.launch({ headless: true,
    ...(process.env.GM_TEST_BROWSER_EXECUTABLE ? { executablePath: process.env.GM_TEST_BROWSER_EXECUTABLE } : {}) });
  async function login(name) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true });
    await context.addInitScript(() => localStorage.setItem('guitar-mentor.locale', 'en'));
    const tab = await context.newPage();
    tab.setDefaultTimeout(30000);
    tab.on('pageerror', error => pageErrors.push(error.message));
    await tab.goto(base + '/library');
    await tab.locator('#local-identifier').fill(name);
    await tab.locator('#local-password').fill('qa170e-synthetic-password');
    await tab.locator('.auth-form button[type=submit]').click();
    await tab.locator('.library-page').waitFor();
    return { context, tab };
  }
  const alice = await login(process.env.GM_TEST_LOGIN_A);
  page = alice.tab;
  const panel = page.locator('.conversion-panel');
  const state = name => page.waitForFunction(expected => document.querySelector('.conversion-heading strong')?.textContent === expected, name);
  const json = async url => {
    const response = await alice.context.request.get(base + url);
    assert.equal(response.status(), 200, url);
    return response.json();
  };
  async function upload(filename, title) {
    await page.getByRole('button', { name: 'Add material', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.locator('input[type=file]').setInputFiles(path.join(corpus, filename));
    await dialog.getByLabel('Title', { exact: true }).fill(title);
    const received = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/library/materials');
    await dialog.getByRole('button', { name: 'Upload', exact: true }).click();
    const response = await received;
    assert.equal(response.status(), 201, await response.text());
    const material = await response.json();
    await page.locator('.sharing h2').filter({ hasText: title }).waitFor();
    return material;
  }
  async function start(material) {
    const received = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/' + material.id + '/conversion-jobs'));
    await panel.getByRole('button', { name: /^Create score and audio/ }).click();
    const response = await received;
    assert.equal(response.status(), 202, await response.text());
    return response.json();
  }
  async function download(asset) {
    const card = page.locator('.sharing > .asset-list > article').filter({ has: page.locator('strong', { hasText: asset.originalName }) });
    const received = page.waitForEvent('download');
    await card.getByRole('button', { name: 'Download', exact: true }).click();
    const result = await received;
    assert.equal(result.suggestedFilename(), asset.originalName);
    const bytes = await readFile(await result.path());
    assert.equal(bytes.length, asset.byteSize);
    return bytes;
  }
  async function verify(material, job, filename, expectedState = 'SUCCEEDED') {
    const fresh = await json('/api/v1/library/materials/' + material.id);
    const done = await json('/api/v1/library/materials/' + material.id + '/conversion-jobs/' + job.id);
    assert.equal(done.state, expectedState);
    assert.equal(fresh.visibility, 'PRIVATE');
    const original = fresh.files.find(f => f.id === material.files[0].id);
    assert.ok(original);
    assert.equal(sha(await download(original)), sha(await readFile(path.join(corpus, filename))));
    if (expectedState === 'SUCCEEDED') {
      const pdf = fresh.files.find(f => f.id === done.pdfAssetId && f.role === 'DERIVED_SCORE_PDF');
      const wav = fresh.files.find(f => f.id === done.audioAssetId && f.role === 'SYNTHESIZED_PREVIEW');
      assert.equal(fresh.files.length, 3); assert.ok(pdf); assert.ok(wav);
      assert.equal((await download(pdf)).subarray(0, 5).toString(), '%PDF-');
      const audio = await download(wav);
      assert.equal(audio.subarray(0, 4).toString(), 'RIFF');
      assert.equal(audio.subarray(8, 12).toString(), 'WAVE');
    } else assert.equal(fresh.files.length, 1);
    report.push({ title: material.title, state: done.state, attemptCount: done.attemptCount, materialId: material.id, jobId: job.id, passed: true });
    return { fresh, done };
  }
  let ownerResource;
  for (const format of ['gp3', 'gp4', 'gp5']) {
    const filename = 'single.' + format;
    const material = await upload(filename, '170E browser ' + format);
    const job = await start(material);
    await state('Completed');
    await panel.locator('.conversion-notice').waitFor();
    ownerResource = await verify(material, job, filename);
  }
  const selected = await upload('tracks.gp5', '170E track selection');
  const selectionJob = await start(selected);
  await state('Waiting for track selection');
  await panel.locator('input[type=radio]').first().check();
  await panel.getByRole('button', { name: 'Continue with this track', exact: true }).click();
  await state('Completed');
  await panel.locator('.conversion-notice').waitFor();
  await verify(selected, selectionJob, 'tracks.gp5');
  // A real page reload restores the same durable job; no new conversion or output pair.
  await page.reload();
  await page.locator('.name-cell button').filter({ hasText: selected.title }).click();
  await state('Completed');
  await panel.locator('.conversion-notice').waitFor();
  await page.screenshot({ path: path.join(evidence, 'completed.png'), fullPage: true });
  const cancelled = await upload('tracks.gp5', '170E cancellation');
  const cancelJob = await start(cancelled);
  await state('Waiting for track selection');
  await panel.getByRole('button', { name: 'Cancel conversion', exact: true }).click();
  await state('Cancelled');
  await verify(cancelled, cancelJob, 'tracks.gp5', 'CANCELLED');
  // The test server uses a dedicated tag. Remove only that tag to produce a real Docker failure.
  const workerImage = process.env.GM_TEST_BROWSER_IMAGE;
  assert.match(workerImage, /^gm-conversion-worker:170e-browser-[a-f0-9-]+$/);
  docker('tag', workerImage, testImage);
  docker('image', 'rm', workerImage);
  imageRemoved = true;
  try {
    const retryMaterial = await upload('single.gp5', '170E retry');
    const retryJob = await start(retryMaterial);
    await state('Failed');
    assert.match(await panel.innerText(), /conversion service is unavailable/i);
    docker('tag', testImage, workerImage); imageRemoved = false;
    await panel.getByRole('button', { name: 'Retry conversion', exact: true }).click();
    await state('Completed');
    await panel.locator('.conversion-notice').waitFor();
    const result = await verify(retryMaterial, retryJob, 'single.gp5');
    assert.equal(result.done.attemptCount, 2);
  } finally {
    if (imageRemoved) { docker('tag', testImage, workerImage); imageRemoved = false; }
  }
  const bob = await login(process.env.GM_TEST_LOGIN_B);
  await bob.tab.locator('.library-empty').waitFor();
  assert.equal(await bob.tab.locator('.conversion-panel').count(), 0);
  const guest = await browser.newContext();
  const paths = ['/api/v1/library/materials/' + ownerResource.fresh.id,
    '/api/v1/library/materials/' + ownerResource.fresh.id + '/conversion-jobs/' + ownerResource.done.id,
    ...ownerResource.fresh.files.map(f => f.downloadUrl)];
  for (const url of paths) {
    assert.equal((await bob.context.request.get(base + url)).status(), 404, 'Other account: ' + url);
    assert.equal((await guest.request.get(base + url)).status(), 401, 'Guest: ' + url);
  }
  assert.deepEqual(pageErrors, []);
  report.push({ name: 'independent owner/other account/guest sessions', passed: true });
  console.log('170-E browser scenarios PASS: ' + report.length);
} catch (error) {
  if (page) await page.screenshot({ path: path.join(evidence, 'failure.png'), fullPage: true }).catch(() => {});
  report.push({ passed: false, error: String(error), pageErrors });
  throw error;
} finally {
  if (imageRemoved) docker('tag', testImage, process.env.GM_TEST_BROWSER_IMAGE);
  try { docker('image', 'rm', testImage); } catch { /* backup may not have been created */ }
  await writeFile(path.join(evidence, 'browser-report.json'), JSON.stringify(report, null, 2));
  await browser?.close();
  await server.close();
}
