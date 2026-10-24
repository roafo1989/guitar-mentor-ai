import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/core/auth/auth.ts', import.meta.url), 'utf8');
const vueUrl = import.meta.resolve('vue');
const transpiled = ts.transpileModule(source.replaceAll('import.meta.env', '({ DEV: false })'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText.replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(vueUrl));
const authModuleUrl = 'data:text/javascript,' + encodeURIComponent(transpiled);

function response(status, body = null) {
  return { status, ok: status >= 200 && status < 300,
    json: async () => body, clone() { return response(status, body); } };
}
async function fixture() {
  const calls = [];
  const previousWindow = globalThis.window;
  const previousFetch = globalThis.fetch;
  globalThis.window = {
    location: { origin: 'https://app.example' },
    fetch: async (url, options = {}) => {
      const path = typeof url === 'string' ? url : url.url;
      calls.push({ path, options });
      if (path === '/api/v1/csrf') {
        return response(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-secret' });
      }
      if (path === '/api/v1/me') {
        return response(200, { accountId: 'account-a', learnerId: 'learner-a', role: 'LEARNER' });
      }
      if (path === '/api/v1/learner-data') return response(401);
      if (path === '/api/v1/disabled') return response(403, { code: 'ACCOUNT_NOT_ADMITTED' });
      if (path === '/api/v1/ordinary-forbidden') return response(403, { code: 'BUSINESS_RULE_DENIED' });
      return response(200);
    },
  };
  const mod = await import(authModuleUrl + '#fixture-' + Math.random());
  mod.installAuthenticatedFetch(() => { mod.auth.expired = true; });
  globalThis.fetch = globalThis.window.fetch;
  return { mod, calls, restore: () => { globalThis.window = previousWindow; globalThis.fetch = previousFetch; } };
}
test('unsafe multipart API request uses server CSRF and never rewrites Content-Type', async () => {
  const { calls, restore } = await fixture();
  try {
    await window.fetch('/api/v1/learners/learner-a/upload', { method: 'POST', body: 'multipart' });
    assert.equal(calls[0].path, '/api/v1/csrf');
    assert.equal(calls[1].options.headers.get('X-XSRF-TOKEN'), 'csrf-secret');
    assert.equal(calls[1].options.headers.has('Content-Type'), false);
    assert.equal(calls[1].options.credentials, 'same-origin');
  } finally { restore(); }
});
test('session loss clears actor and cached CSRF before any further request', async () => {
  const { mod, calls, restore } = await fixture();
  try {
    await mod.refreshActor();
    assert.equal(mod.auth.actor.learnerId, 'learner-a');
    await window.fetch('/api/v1/learner-data');
    assert.equal(mod.auth.actor, null);
    assert.equal(mod.auth.expired, true);
    await window.fetch('/api/v1/learners/learner-a/upload', { method: 'POST' });
    assert.equal(calls.filter(x => x.path === '/api/v1/csrf').length, 1);
  } finally { restore(); }
});
test('logout posts with verified CSRF and clears current actor', async () => {
  const { mod, calls, restore } = await fixture();
  try {
    await mod.refreshActor();
    let cleared = false;
    await mod.logout(() => { cleared = true; });
    const outgoing = calls.find(x => x.path === '/logout');
    assert.equal(outgoing.options.method, 'POST');
    assert.equal(outgoing.options.headers['X-XSRF-TOKEN'], 'csrf-secret');
    assert.equal(mod.auth.actor, null);
    assert.equal(cleared, true);
  } finally { restore(); }
});

test('account disable clears session but unrelated business 403 preserves actor', async () => {
  const { mod, restore } = await fixture();
  try {
    await mod.refreshActor();
    await window.fetch('/api/v1/ordinary-forbidden');
    assert.equal(mod.auth.actor?.learnerId, 'learner-a');
    await window.fetch('/api/v1/disabled');
    assert.equal(mod.auth.actor, null);
    assert.equal(mod.auth.expired, true);
  } finally { restore(); }
});

test('password reset links do not render secrets as form inputs or retain them in login', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.match(gate, /tokenFromLink = ref\(false\)/);
  assert.match(gate, /v-if="!tokenFromLink" id="reset-token"/);
  assert.match(gate, /v-if="!tokenFromLink" id="verify-token"/);
  assert.match(gate, /takeEmailActionLink\(\)/);
  assert.match(gate, /identifier\.value = ''; email\.value = '';/);
  assert.match(gate, /clearTokenLikeIdentifier/);
  assert.match(gate, /if \(identifierInput\.value\) identifierInput\.value\.value = '';/);
});

test('SPA reset links are scrubbed and shown even when an authenticated actor exists', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.match(gate, /window\.addEventListener\('hashchange', consumeAuthFragment\)/);
  assert.match(gate, /window\.removeEventListener\('hashchange', consumeAuthFragment\)/);
  assert.match(gate, /captureEmailActionLink\(\)/);
  const startup = await readFile(new URL('../src/main.ts', import.meta.url), 'utf8');
  assert.ok(startup.indexOf('captureEmailActionLink();') < startup.indexOf('app.use(router)'), 'URL is scrubbed before installing router');
  assert.match(gate, /auth\.actor && !\(mode === 'reset' && tokenFromLink\)/);
  assert.match(gate, /clearActor\(\);\s*setMode\('login'\)/);
});

test('boot pre-router capture removes reset token from URL while preserving it once in memory', async () => {
  const source = await readFile(new URL('../src/core/auth/auth-link.ts', import.meta.url), 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  const token = 'a'.repeat(64);
  let current = 'https://guitar-mentor/#authAction=reset&token=' + token;
  let historyState = { position: 4 };
  const previous = globalThis.window;
  globalThis.window = {
    location: { get href() { return current; } },
    history: {
      get state() { return historyState; },
      replaceState(state, _title, path) {
        historyState = state;
        current = new URL(path, 'https://guitar-mentor').href;
      },
    },
  };
  try {
    const mod = await import('data:text/javascript,' + encodeURIComponent(js));
    mod.captureEmailActionLink();
    assert.equal(current, 'https://guitar-mentor/');
    assert.deepEqual(historyState, { position: 4 });
    assert.deepEqual(mod.takeEmailActionLink(), { action: 'reset', token });
    assert.equal(mod.takeEmailActionLink(), null);
  } finally { globalThis.window = previous; }
});
