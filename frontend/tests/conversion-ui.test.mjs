import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import ts from "typescript";
import { createRenderer, reactive, h, nextTick } from "vue";
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


const vueUrl = import.meta.resolve("vue");
const asModule = code => "data:text/javascript," + encodeURIComponent(code);
let mountedPanel;
async function loadPanel() {
  if (mountedPanel) return mountedPanel;
  const { parse, compileScript } = await import("vue/compiler-sfc");
  const descriptor = parse(panel, { filename: "ConversionJobPanel.vue" }).descriptor;
  const script = compileScript(descriptor, { id: "conversion-panel-test", inlineTemplate: false });
  const js = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_full, _q, id) => {
    const dependencies = {
      vue: vueUrl,
      "vue-i18n": asModule("export const useI18n=()=>({t:key=>key,te:()=>true});"),
      "../../core/auth/auth": asModule("export const auth=globalThis.__conversionPanelAuth;"),
      "./conversion.service": asModule("export const ConversionFailure=globalThis.__conversionPanelFailure;export const conversionService=globalThis.__conversionPanelApi;"),
      "./library-errors": asModule("export const libraryError=(_e,t)=>t('conversion.generic');"),
    };
    assert.ok(dependencies[id], "unexpected panel dependency " + id);
    return "from " + JSON.stringify(dependencies[id]);
  });
  mountedPanel = (await import(asModule(mapped))).default;
  return mountedPanel;
}
function renderer() {
  const node = tag => ({ tag, parent: null, children: [], text: "", props: {} });
  const remove = n => {
    if (!n.parent) return;
    const siblings = n.parent.children, i = siblings.indexOf(n);
    if (i >= 0) siblings.splice(i, 1);
    n.parent = null;
  };
  return createRenderer({
    createElement: node,
    createText: value => ({ ...node("#text"), text: value }),
    createComment: value => ({ ...node("#comment"), text: value }),
    setText(n, value) { n.text = value; },
    setElementText(n, value) { n.text = value; n.children = []; },
    patchProp(n, key, _old, value) { n.props[key] = value; },
    insert(n, parent, anchor = null) {
      if (n.parent) remove(n);
      n.parent = parent;
      const i = parent.children.indexOf(anchor);
      if (i < 0) parent.children.push(n); else parent.children.splice(i, 0, n);
    },
    remove, parentNode: n => n.parent,
    nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
  });
}
async function flushVue() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } }

test("mounted conversion panel creates, selects, cancels, retries and restores a job per account", async () => {
  const originalStorage = globalThis.sessionStorage;
  const values = new Map();
  globalThis.sessionStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
  };
  const auth = reactive({ actor: { accountId: "A" } });
  const calls = [];
  const queued = { ...job, state: "QUEUED", revision: 1 };
  const awaiting = { ...job, state: "AWAITING_TRACK_SELECTION", revision: 2,
    tracks: [{ trackIndex: 3, name: "Guitar", guitar: true, instrument: "SteelString" }] };
  const fresh = { id: materialId, title: "Test", type: "COMPOSITION", author: null,
    state: "READY", visibility: "PRIVATE", reviewStatus: "UNREVIEWED", revision: 8,
    createdAt: "2026-11-01T00:00:00Z", updatedAt: "2026-11-01T00:00:00Z",
    files: [
      { id: pdfId, originalName: "score.pdf", format: "PDF", byteSize: 5, role: "DERIVED_SCORE_PDF" },
      { id: audioId, originalName: "preview.wav", format: "WAV", byteSize: 5, role: "SYNTHESIZED_PREVIEW" },
    ] };
  const api = {
    async create(...args) { calls.push(["create", ...args]); return queued; },
    async get(...args) { calls.push(["get", ...args]); return { ...queued, state: "RUNNING", revision: 4 }; },
    async selectTrack(...args) { calls.push(["selectTrack", ...args]); return { ...queued, revision: 3 }; },
    async cancel(...args) { calls.push(["cancel", ...args]); return { ...queued, state: "CANCEL_REQUESTED", revision: 5 }; },
    async retry(...args) { calls.push(["retry", ...args]); return { ...queued, revision: 6 }; },
    async refreshMaterial(...args) { calls.push(["refreshMaterial", ...args]); return fresh; },
  };
  globalThis.__conversionPanelAuth = auth;
  globalThis.__conversionPanelApi = api;
  globalThis.__conversionPanelFailure = class extends Error {
    constructor(status, code, kind = "HTTP") { super(kind); this.status = status; this.code = code; this.kind = kind; }
  };
  const source = { id: sourceId, originalName: "song.gp5", format: "GP5",
    role: "SCORE_ORIGINAL", processingState: "READY" };
  const material = { id: materialId, revision: 7, files: [source] };
  let app, binding, emitted;
  try {
    const Base = await loadPanel();
    const Wrapper = { setup() {
      binding = Base.setup({ material }, { emit: (event, value) => { if (event === "updated") emitted = value; } });
      return () => h("section");
    } };
    app = renderer().createApp(Wrapper);
    app.mount({ tag: "root", children: [], parent: null });
    await flushVue();
    await binding.start(source);
    assert.equal(binding.job.value.id, jobId);
    assert.equal(calls[0][0], "create");
    assert.equal(calls[0][2], sourceId);
    assert.equal(calls[0][1].revision, 7);
    assert.ok(values.has("gm.conversion.A." + materialId));

    binding.job.value = awaiting;
    binding.selectedTrack.value = 3;
    binding.chooseTrack();
    await flushVue();
    assert.ok(calls.some(c => c[0] === "selectTrack" && c[2] === 3));

    binding.job.value = { ...queued, state: "RUNNING", revision: 4 };
    binding.cancel();
    await flushVue();
    assert.ok(calls.some(c => c[0] === "cancel"));

    binding.job.value = { ...queued, state: "FAILED", retryable: true, revision: 5 };
    binding.retry();
    await flushVue();
    assert.ok(calls.some(c => c[0] === "retry"));

    const succeeded = { ...queued, state: "SUCCEEDED", pdfAssetId: pdfId,
      audioAssetId: audioId, revision: 9 };
    await binding.applyJob(succeeded, binding.generation, "A");
    assert.equal(emitted, fresh);
    assert.equal(binding.notice.value, "conversion.completed");
    app.unmount();
    app = null;

    // The stored job is recovered in the same tab after remount/page reload.
    const Wrapper2 = { setup() {
      binding = Base.setup({ material }, { emit: () => {} });
      return () => h("section");
    } };
    app = renderer().createApp(Wrapper2);
    app.mount({ tag: "root", children: [], parent: null });
    await flushVue();
    assert.ok(calls.some(c => c[0] === "get" && c[1] === materialId && c[2] === jobId));
    assert.equal(binding.job.value.state, "RUNNING");

    auth.actor = { accountId: "B" };
    await flushVue();
    assert.equal(binding.job.value, null, "account switch clears previous private job state");
    app.unmount();
    app = null;
  } finally {
    app?.unmount();
    globalThis.sessionStorage = originalStorage;
    delete globalThis.__conversionPanelAuth;
    delete globalThis.__conversionPanelApi;
    delete globalThis.__conversionPanelFailure;
  }
});
