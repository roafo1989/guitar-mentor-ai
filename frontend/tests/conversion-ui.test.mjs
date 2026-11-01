import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import ts from "typescript";
import { ApiFailure, isArrayOf, isObject, isString, isUuid } from "../src/shared/api-failure.ts";

const read = path => readFileSync(new URL("../" + path, import.meta.url), "utf8");
const source = read("src/entities/library/conversion.service.ts");
const panel = read("src/entities/library/ConversionJobPanel.vue");
const sharing = read("src/entities/library/MaterialSharing.vue");
const library = read("src/entities/library/library.service.ts");
const ru = JSON.parse(read("src/i18n/ru.json"));
const en = JSON.parse(read("src/i18n/en.json"));

function serviceHarness() {
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  new Function("require", "exports", code)((id) => {
    if (id === "../../shared/api-failure") return { ApiFailure, isArrayOf, isObject, isString, isUuid };
    if (id === "./library.service") return { validMaterial: value => !!value };
    throw Error("unexpected dependency " + id);
  }, exports);
  return exports;
}
const materialId = "11111111-1111-4111-8111-111111111111";
const sourceId = "22222222-2222-4222-8222-222222222222";
const jobId = "33333333-3333-4333-8333-333333333333";
const pdfId = "44444444-4444-4444-8444-444444444444";
const audioId = "55555555-5555-4555-8555-555555555555";
const job = {
  id: jobId, materialId, sourceAssetId: sourceId, sourceFormat: "GP5",
  state: "QUEUED", stage: null, progress: 0, tracks: [], selectedTrackIndex: null,
  cancelRequested: false, attemptCount: 0, retryable: false, errorCode: null,
  pdfAssetId: null, audioAssetId: null, revision: 1,
  createdAt: "2026-11-01T00:00:00Z", updatedAt: "2026-11-01T00:00:00Z",
};
const before = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = before; });
async function withFetch(fn, work) {
  globalThis.fetch = fn;
  try { return await work(); } finally { globalThis.fetch = before; }
}

test("conversion job schema validates safe API representation and rejects malformed tracks/paths", () => {
  const { validConversionJob } = serviceHarness();
  assert.equal(validConversionJob(job), true);
  assert.equal(validConversionJob({ ...job, progress: 101 }), false);
  assert.equal(validConversionJob({ ...job, tracks: [{ trackIndex: -1, name: "x", guitar: true, instrument: null }] }), false);
  assert.equal(validConversionJob({ ...job, pdfAssetId: "/private/path" }), false);
  assert.equal(validConversionJob({ ...job, state: "ARBITRARY" }), false);
});

test("create sends source id, idempotency key and material If-Match using same-origin request", async () => {
  const { conversionService } = serviceHarness();
  const material = { id: materialId, revision: 7 };
  const key = "66666666-6666-4666-8666-666666666666";
  await withFetch(async (url, init) => {
    assert.equal(url, "/api/v1/library/materials/" + materialId + "/conversion-jobs");
    assert.equal(init.method, "POST");
    assert.equal(init.credentials, "same-origin");
    assert.equal(init.cache, "no-store");
    assert.equal(init.headers["If-Match"], '"7"');
    assert.equal(init.headers["Idempotency-Key"], key);
    assert.deepEqual(JSON.parse(init.body), { sourceAssetId: sourceId });
    return Response.json(job, { status: 202 });
  }, async () => assert.equal((await conversionService.create(material, sourceId, key)).id, jobId));
});

test("track selection, cancel and retry use current job revision and exact owner-scoped routes", async () => {
  const { conversionService } = serviceHarness();
  const calls = [];
  await withFetch(async (url, init) => {
    calls.push({ url, init });
    return Response.json(job);
  }, async () => {
    await conversionService.selectTrack(job, 2);
    await conversionService.cancel(job);
    await conversionService.retry(job);
    await conversionService.get(materialId, jobId);
  });
  assert.deepEqual(calls.map(c => c.url), [
    "/api/v1/library/materials/" + materialId + "/conversion-jobs/" + jobId + "/track-selection",
    "/api/v1/library/materials/" + materialId + "/conversion-jobs/" + jobId + "/cancel",
    "/api/v1/library/materials/" + materialId + "/conversion-jobs/" + jobId + "/retry",
    "/api/v1/library/materials/" + materialId + "/conversion-jobs/" + jobId,
  ]);
  for (const c of calls.slice(0, 3)) assert.equal(c.init.headers["If-Match"], '"1"');
  assert.deepEqual(JSON.parse(calls[0].init.body), { trackIndex: 2 });
});

test("conversion failures retain status and allow-listed machine code but never raw server text", async () => {
  const { conversionService, ConversionFailure } = serviceHarness();
  await withFetch(async () => Response.json({ code: "LIBRARY_CONVERSION_DISABLED", message: "private path /srv/secret" }, { status: 503 }),
    async () => assert.rejects(conversionService.get(materialId, jobId), e =>
      e instanceof ConversionFailure && e.status === 503 && e.code === "LIBRARY_CONVERSION_DISABLED"
        && !e.message.includes("/srv/secret")));
  await withFetch(async () => Response.json({ code: "PATH_DISCLOSURE", message: "secret" }, { status: 409 }),
    async () => assert.rejects(conversionService.get(materialId, jobId), e =>
      e instanceof ConversionFailure && e.status === 409 && e.code === null && !e.message.includes("secret")));
});

test("private library recognizes derived PDF and mounts conversion UI only on private material details", () => {
  assert.match(library, /DERIVED_SCORE_PDF/);
  assert.match(sharing, /<ConversionJobPanel :material="material"/);
  assert.match(sharing, /emit\('updated', \$event\)/);
  assert.match(panel, /auth\.actor\?\.accountId/);
  assert.match(panel, /sessionStorage/);
  assert.match(panel, /storePending/);
  assert.match(panel, /readPending/);
  assert.match(panel, /same idempotent POST if the page was reloaded/);
  assert.match(panel, /setTimeout/);
  assert.match(panel, /clearTimeout/);
  assert.match(panel, /onBeforeUnmount/);
  assert.match(panel, /AWAITING_TRACK_SELECTION/);
  assert.match(panel, /conversionService\.selectTrack/);
  assert.match(panel, /conversionService\.cancel/);
  assert.match(panel, /conversionService\.retry/);
  assert.match(panel, /conversionService\.refreshMaterial/);
  assert.doesNotMatch(panel, /window\.open|location\.href|innerHTML/);
});

test("conversion workflow has complete Russian and English copy and explicitly limits current format support", () => {
  for (const locale of [ru, en]) {
    for (const key of ["title","scope","create","unavailable","stale","conflict","notFound","unsupported",
      "network","invalidResponse","invalidResult","completed","selectTrack","confirmTrack","ready",
      "downloadPdf","downloadAudio","cancel","retry","refresh","states","stages","errors"]) {
      assert.ok(locale.conversion[key], "missing conversion." + key);
    }
    for (const state of ["QUEUED","RUNNING","AWAITING_TRACK_SELECTION","CANCEL_REQUESTED","SUCCEEDED",
      "UNSUPPORTED","FAILED","TIMED_OUT","CANCELLED"]) assert.ok(locale.conversion.states[state]);
  }
  assert.ok(ru.community.roles.DERIVED_SCORE_PDF);
  assert.ok(en.community.roles.DERIVED_SCORE_PDF);
});
