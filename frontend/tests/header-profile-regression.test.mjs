import test from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import { readFileSync } from 'node:fs';
import { ApiFailure, asApiFailure, isHttp } from '../src/shared/api-failure.ts';

const code = ts.transpileModule(readFileSync(new URL('../src/stores/learner.store.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function harness(get) {
  const auth = { actor: null };
  const data = new Map();
  const storage = {
    get length() { return data.size; },
    key(i) { return [...data.keys()][i] ?? null; },
    getItem(k) { return data.get(k) ?? null; },
    setItem(k, v) { data.set(k, v); },
    removeItem(k) { data.delete(k); },
  };
  const modules = {
    pinia: { defineStore: (_, setup) => setup },
    vue: { ref: value => ({ value }) },
    '../entities/learner/learner.service': { learnerService: {
      get, create: async () => null, replacePreferences: async () => null,
    } },
    '../shared/api-failure': { ApiFailure, asApiFailure, isHttp },
    '../core/auth/auth': { auth, secureUi: true },
  };
  const exports = {};
  new Function('require', 'exports', code)(
    id => { assert.ok(modules[id], 'unexpected dependency: ' + id); return modules[id]; }, exports,
  );
  const store = exports.useLearnerStore();
  const use = async fn => {
    const old = globalThis.window;
    globalThis.window = { localStorage: storage };
    try { return await fn(); } finally { globalThis.window = old; }
  };
  return { auth, store, storage, use };
}
const actor = (accountId, learnerId) => ({ accountId, learnerId, role: 'LEARNER' });
const profile = (id, name) => ({ id, displayName: name });

test('header profile restores on authenticated route, concurrent requests reuse one GET', async () => {
  const pending = deferred();
  let requests = 0;
  const h = harness(() => { requests++; return pending.promise; });
  h.auth.actor = actor('A', 'learner-A');
  await h.use(async () => {
    const first = h.store.restore();
    const second = h.store.restore();
    assert.equal(requests, 1);
    assert.equal(first, second);
    assert.equal(h.store.busy.value, true);
    pending.resolve(profile('learner-A', 'Alice'));
    await Promise.all([first, second]);
    assert.equal(h.store.learner.value.displayName, 'Alice');
    assert.equal(h.store.busy.value, false);
  });
});

test('same actor retains confirmed name throughout refresh and transient error', async () => {
  const slow = deferred();
  let count = 0;
  const h = harness(() => ++count === 1 ? Promise.resolve(profile('learner-A', 'Alice')) : slow.promise);
  h.auth.actor = actor('A', 'learner-A');
  await h.use(async () => {
    await h.store.restore();
    const refreshing = h.store.restore();
    assert.equal(h.store.learner.value.displayName, 'Alice');
    slow.reject(new ApiFailure('HTTP', 500));
    await refreshing;
    assert.equal(h.store.learner.value.displayName, 'Alice');
    assert.equal(h.store.busy.value, false);
    assert.equal(h.store.error.value.status, 500);
  });
});

test('logout and account switch invalidate late old GET even on same account re-login', async () => {
  const a1 = deferred(), b = deferred(), a2 = deferred();
  let calls = 0;
  const h = harness(() => [a1, b, a2][calls++].promise);
  await h.use(async () => {
    h.auth.actor = actor('A', 'learner-A');
    const p1 = h.store.restore();
    h.auth.actor = actor('B', 'learner-B');
    const p2 = h.store.restore();
    a1.resolve(profile('learner-A', 'Alice'));
    await p1;
    assert.equal(h.store.learner.value, null);
    b.resolve(profile('learner-B', 'Bob'));
    await p2;
    assert.equal(h.store.learner.value.displayName, 'Bob');
    h.auth.actor = null;
    h.store.clearLocalSelection();
    h.auth.actor = actor('A', 'learner-A');
    const p3 = h.store.restore();
    assert.equal(h.store.learner.value, null);
    a2.resolve(profile('learner-A', 'Alice again'));
    await p3;
    assert.equal(h.store.learner.value.displayName, 'Alice again');
  });
});

test('secure 404 shows a real failure rather than silently using generic profile', async () => {
  const h = harness(async () => { throw new ApiFailure('HTTP', 404); });
  h.auth.actor = actor('A', 'learner-A');
  await h.use(() => h.store.restore());
  assert.equal(h.store.learner.value, null);
  assert.equal(h.store.error.value.status, 404);
  assert.equal(h.store.busy.value, false);
});

test('retry after failed refresh fetches once and recovers confirmed name', async () => {
  let count = 0;
  const h = harness(async () => {
    if (++count === 1) throw new ApiFailure('NETWORK');
    return profile('learner-A', 'Alice');
  });
  h.auth.actor = actor('A', 'learner-A');
  await h.use(async () => {
    await h.store.restore();
    assert.ok(h.store.error.value);
    await h.store.restore();
    assert.equal(count, 2);
    assert.equal(h.store.error.value, null);
    assert.equal(h.store.learner.value.displayName, 'Alice');
  });
});

test('profile changes update displayed name without replacing account identity', async () => {
  const h = harness(async () => profile('learner-A', 'Updated name'));
  h.auth.actor = actor('A', 'learner-A');
  await h.use(() => h.store.restore());
  assert.equal(h.store.learner.value.displayName, 'Updated name');
  assert.equal(h.auth.actor.accountId, 'A');
});

test('App header never uses account ID as avatar fallback and owns route independent restore', () => {
  const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8');
  assert.match(source, /watch\(\(\) => auth\.actor/);
  assert.match(source, /learnerStore\.restore\(\)/);
  assert.match(source, /headerInitial/);
  assert.doesNotMatch(source, /displayName\s*\|\|\s*auth\.actor\?\.accountId/);
});
