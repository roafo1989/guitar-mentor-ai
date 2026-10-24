import test from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import { readFileSync } from 'node:fs';
import { ApiFailure, asApiFailure, isHttp } from '../src/shared/api-failure.ts';

const code = ts.transpileModule(
  readFileSync(new URL('../src/stores/learner.store.ts', import.meta.url), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
).outputText;

function harness(get) {
  const data = new Map([
    ['guitar-mentor.learnerId', 'saved-id'],
    ['guitar-mentor.diagnostic.saved-id.ACOUSTIC_GUITAR', 'keep-session'],
  ]);
  const writes = [];
  const storage = {
    getItem(k) { return data.get(k) ?? null; },
    setItem(k, v) { writes.push(['set', k]); data.set(k, v); },
    removeItem(k) { writes.push(['remove', k]); data.delete(k); },
  };
  const modules = {
    pinia: { defineStore: (_id, setup) => setup },
    vue: { ref: value => ({ value }) },
    '../entities/learner/learner.service': { learnerService: {
      get, create: async () => null, replacePreferences: async () => null,
    } },
    '../shared/api-failure': { ApiFailure, asApiFailure, isHttp },
    '../core/auth/auth': { auth: { actor: null }, secureUi: false },
  };
  const exports = {};
  new Function('require', 'exports', code)(
    id => { assert.ok(modules[id], 'unexpected dependency: ' + id); return modules[id]; }, exports,
  );
  return { store: exports.useLearnerStore(), data, writes, storage };
}
async function withWindow(storage, action) {
  const original = globalThis.window;
  globalThis.window = { localStorage: storage };
  try { await action(); } finally { globalThis.window = original; }
}
test('only learner GET 404 clears the learner, never its session or draft', async () => {
  const h = harness(async () => { throw new ApiFailure('HTTP', 404); });
  await withWindow(h.storage, async () => h.store.restore());
  assert.equal(h.storage.getItem('guitar-mentor.learnerId'), null);
  assert.equal(h.storage.getItem('guitar-mentor.diagnostic.saved-id.ACOUSTIC_GUITAR'), 'keep-session');
  assert.equal(h.store.error.value, null);
  assert.deepEqual(h.writes, [['remove', 'guitar-mentor.learnerId']]);
});
test('HTTP500, offline and parse failure retain existing learner selection', async () => {
  for (const failure of [new ApiFailure('HTTP', 500), new ApiFailure('NETWORK'), new ApiFailure('INVALID_RESPONSE')]) {
    const h = harness(async () => { throw failure; });
    await withWindow(h.storage, async () => h.store.restore());
    assert.equal(h.storage.getItem('guitar-mentor.learnerId'), 'saved-id');
    assert.equal(h.store.error.value, failure);
    assert.deepEqual(h.writes, []);
  }
});
test('late 404 never wipes a newer selection', async () => {
  let reject;
  const request = new Promise((_, fail) => { reject = fail; });
  const h = harness(() => request);
  await withWindow(h.storage, async () => {
    const pending = h.store.restore();
    h.storage.setItem('guitar-mentor.learnerId', 'newer-id');
    reject(new ApiFailure('HTTP', 404));
    await pending;
    assert.equal(h.storage.getItem('guitar-mentor.learnerId'), 'newer-id');
    assert.ok(!h.writes.some(([action]) => action === 'remove'));
  });
});
