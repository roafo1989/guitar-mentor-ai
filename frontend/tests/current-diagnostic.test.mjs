import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import ts from 'typescript';

const source = readFileSync(new URL('../src/entities/diagnostic/current-diagnostic.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

function harness({ selected = null, get, current } = {}) {
  const calls = [];
  const values = new Map();
  const key = 'guitar-mentor.diagnostic.learner-1.ACOUSTIC_GUITAR';
  if (selected) values.set(key, selected);
  const storage = {
    getItem: k => values.get(k) ?? null,
    removeItem: k => { calls.push('remove'); values.delete(k); },
    setItem: (k, v) => { calls.push('set'); values.set(k, v); },
  };
  const api = {
    get: async (...args) => { calls.push('get'); return get?.(...args); },
    current: async (...args) => { calls.push('current'); return current?.(...args) ?? null; },
  };
  const exports = {};
  new Function('require', 'exports', compiled)(name => {
    if (name === './diagnostic.service') return { diagnosticService: api };
    if (name === '../../shared/api-failure') return { isHttp: (error, status) => error?.status === status };
    throw Error('Unexpected dependency ' + name);
  }, exports);
  return { ...exports, calls, storage, values, key };
}

test('#211: no browser key resolves existing server session without POST', async () => {
  const existing = { id: 'server-session', status: 'TASK_ASSIGNED' };
  const h = harness({ current: async () => existing });
  assert.deepEqual(await h.resolveCurrentDiagnostic('learner-1', h.storage), existing);
  assert.equal(h.values.get(h.key), 'server-session');
  assert.deepEqual(h.calls, ['current', 'set']);
});

test('#211: 204 means no session; never create one', async () => {
  const h = harness();
  assert.equal(await h.resolveCurrentDiagnostic('learner-1', h.storage), null);
  assert.deepEqual(h.calls, ['current']);
});

test('#211: old 404 clears selection and falls back to current', async () => {
  const existing = { id: 'recovered', status: 'READY_FOR_TASK' };
  const h = harness({ selected: 'old', get: async () => { throw Object.assign(new Error('404'), { status: 404 }); }, current: async () => existing });
  assert.equal((await h.resolveCurrentDiagnostic('learner-1', h.storage)).id, existing.id);
  assert.equal(h.values.get(h.key), existing.id);
  assert.deepEqual(h.calls, ['get', 'remove', 'current', 'set']);
});

test('#211: offline/500 never becomes a false fresh start', async () => {
  const h = harness({ current: async () => { throw Object.assign(new Error('offline'), { status: 500 }); } });
  await assert.rejects(h.resolveCurrentDiagnostic('learner-1', h.storage));
  assert.deepEqual(h.calls, ['current']);
  assert.equal(h.values.size, 0);
});

test('#211: stale actor cannot save a session after logout', async () => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  let active = true;
  const h = harness({ current: () => pending });
  const promise = h.resolveCurrentDiagnostic('learner-1', h.storage, () => active);
  active = false;
  release({ id: 'late', status: 'TASK_ASSIGNED' });
  await assert.rejects(promise, error => error.name === 'AbortError');
  assert.equal(h.values.size, 0);
});

test('#211: existing selected session takes priority without current lookup', async () => {
  const selected = { id: 'selected', status: 'COLLECTING_EVIDENCE' };
  const h = harness({ selected: 'selected', get: async () => selected });
  assert.deepEqual(await h.resolveCurrentDiagnostic('learner-1', h.storage), selected);
  assert.deepEqual(h.calls, ['get']);
});

test('#216: archived browser key resolves successor only after authoritative GET succeeds', async () => {
  const next = { id: 'next-session', status: 'TASK_ASSIGNED' };
  const h = harness({ selected: 'archived', get: async () => ({ id: 'archived', status: 'ARCHIVED' }), current: async () => next });
  assert.deepEqual(await h.resolveCurrentDiagnostic('learner-1', h.storage), next);
  assert.equal(h.values.get(h.key), 'next-session');
  assert.deepEqual(h.calls, ['get', 'current', 'set']);
});

test('#216: failed server current cannot erase an archived selection', async () => {
  const h = harness({ selected: 'archived', get: async () => ({ id: 'archived', status: 'ARCHIVED' }),
    current: async () => { throw Object.assign(new Error('offline'), { status: 500 }); } });
  await assert.rejects(h.resolveCurrentDiagnostic('learner-1', h.storage), /offline/);
  assert.equal(h.values.get(h.key), 'archived');
  assert.deepEqual(h.calls, ['get', 'current']);
});

test('#216: successful no-current response clears stale archived selection without new session', async () => {
  const h = harness({ selected: 'archived', get: async () => ({ id: 'archived', status: 'ARCHIVED' }), current: async () => null });
  assert.equal(await h.resolveCurrentDiagnostic('learner-1', h.storage), null);
  assert.equal(h.storage.getItem(h.key), null);
  assert.deepEqual(h.calls, ['get', 'current', 'remove']);
});
