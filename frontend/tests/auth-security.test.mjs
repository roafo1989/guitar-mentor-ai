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
  assert.match(gate, /v-if="!tokenFromLink && manualCode"/);
  assert.match(gate, /id="reset-token"/);
  assert.match(gate, /id="verify-token"/);
  assert.match(gate, /v-if="tokenFromLink \|\| manualCode"/);
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
  assert.match(gate, /auth\.actor && !emailActionInProgress/);
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

test('router scrubs reset token before creating browser history (ESM import ordering)', async () => {
  const router = await readFile(new URL('../src/router/index.ts', import.meta.url), 'utf8');
  const capture = router.indexOf('captureEmailActionLink();');
  const createHistory = router.indexOf('createWebHistory(),');
  assert.ok(capture >= 0 && createHistory > capture, 'scrub must happen before createWebHistory');
  assert.match(router, /import \{ captureEmailActionLink \} from '\.\.\/core\/auth\/auth-link'/);
});

test('email recovery hides signed-in home and home does not duplicate retry buttons', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  const home = await readFile(new URL('../src/core/home/HomeView.vue', import.meta.url), 'utf8');
  assert.match(gate, /auth\.actor && !emailActionInProgress/);
  assert.match(gate, /auth\.ready \|\| emailActionInProgress/);
  assert.match(home, /v-else-if="!restoreFailed"/);
  assert.doesNotMatch(home, /v-else-if="restoreFailed" type="button" class="home-primary"/);
});


test('single HTTPS origin keeps email action URLs on guitar-mentor', async () => {
  const router = await readFile(new URL('../src/router/index.ts', import.meta.url), 'utf8');
  const launcher = await readFile(new URL('../../scripts/local-auth-unified.ps1', import.meta.url), 'utf8');
  assert.doesNotMatch(router, /redirectUnifiedLocalAlias/);
  assert.match(router, /captureEmailActionLink\(\)/);
  assert.match(launcher, /GUITAR_MENTOR_PUBLIC_ORIGIN = 'https:\/\/guitar-mentor'/);
  assert.doesNotMatch(launcher, /VITE_GUITAR_MENTOR_UNIFIED_LOCAL = 'true'/);
});

test('OAuth handoff ticket stripped before Vue Router and never exposed to login inputs', async () => {
  const moduleSource = await readFile(new URL('../src/core/auth/oauth-bridge-ticket.ts', import.meta.url), 'utf8');
  const js = ts.transpileModule(moduleSource, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;
  const raw = 'b'.repeat(64);
  let href = 'https://guitar-mentor/#oauthBridge=' + raw;
  const prev = globalThis.window;
  globalThis.window = {
    location: { get href() { return href; } },
    history: { state: { position: 0 }, replaceState(_state, _name, path) {
      href = new URL(path, 'https://guitar-mentor').href;
    }},
  };
  try {
    const bridge = await import('data:text/javascript,' + encodeURIComponent(js));
    bridge.captureOAuthBridgeTicket();
    assert.equal(href, 'https://guitar-mentor/');
    assert.equal(bridge.takeOAuthBridgeTicket(), raw);
    assert.equal(bridge.takeOAuthBridgeTicket(), null);
  } finally { globalThis.window = prev; }
  const router = await readFile(new URL('../src/router/index.ts', import.meta.url), 'utf8');
  assert.ok(router.indexOf('captureOAuthBridgeTicket();') < router.indexOf('createWebHistory()'));
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.match(gate, /takeOAuthBridgeTicket\(\)/);
  assert.match(gate, /\/api\/v1\/oauth-bridge\/consume/);
  const auth = await readFile(new URL('../src/core/auth/auth.ts', import.meta.url), 'utf8');
  assert.match(auth, /\/api\/v1\/oauth-bridge\/start/);
  assert.match(auth, /localhost:5173/);
});

test('AUTH-03 exposes one-step Google sign-in without a Google tab or local validation', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.doesNotMatch(gate, /mode === 'google'|setMode\('google'\)/);
  assert.match(gate, /type="button" :disabled="busy \|\| oauthPending" :aria-busy="oauthPending" @click="handleGoogle"/);
  assert.match(gate, /function handleGoogle\(\): void/);
  assert.match(gate, /if \(busy.value \|\| oauthPending.value\) return;/);
  assert.match(gate, /login\(\);/);
  assert.match(gate, /g-logo\.png/);
  assert.match(gate, /Continue with Google/);
  assert.match(gate, /New here\? Create an account/);
  assert.match(gate, /Already have an account\? Sign in/);
});

test('AUTH-03 protects post-submit state and preserves honest generic email responses', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.match(gate, /const epoch = \+\+requestEpoch.value;/);
  assert.match(gate, /if \(!mounted \|\| epoch !== requestEpoch.value\) return false;/);
  assert.match(gate, /If registration can proceed with these details/);
  assert.match(gate, /If an account exists, you will receive reset instructions/);
  assert.doesNotMatch(gate, /resend-verification|local-auth\/resend/);
  assert.match(gate, /window\.history\.replaceState/);
  assert.match(gate, /'bridge_start_failed'/);
  assert.match(gate, /emailActionInProgress.value = next === 'verify' \\|\\| next === 'reset' \\|\\| next === 'expired-link'/);
});

test('AUTH-03 deploys exact approved versioned assets instead of legacy favicon', async () => {
  const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8');
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  const { stat } = await import('node:fs/promises');
  for (const name of ['32', '64', '128']) {
    const info = await stat(new URL('../public/brand/guitar-mentor-logo-' + name + '.png', import.meta.url));
    assert.ok(info.size > 1000, name);
  }
  await stat(new URL('../public/vendor/google-signin/g-logo.png', import.meta.url));
  await stat(new URL('../public/vendor/google-signin/GoogleSans-Medium-auth.ttf', import.meta.url));
  assert.match(html, /guitar-mentor-logo-32\.png\?v=198/);
  assert.match(html, /guitar-mentor-logo-64\.png\?v=198/);
  assert.match(app, /src="\/brand\/guitar-mentor-logo-128\.png"/);
  assert.match(gate, /src="\/brand\/guitar-mentor-logo-128\.png"/);
});

test('AUTH-03 regression: malformed email links never unmask authenticated content', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.match(gate, /emailActionInProgress\.value = true;\s*if \(!link\.token/);
  assert.match(gate, /mode\.value = 'expired-link';/);
  assert.match(gate, /emailActionInProgress\.value = next === 'verify' \|\| next === 'reset' \|\| next === 'expired-link';/);
  assert.match(gate, /<slot v-if="localPrototype \|\| \(auth\.ready && auth\.actor && !emailActionInProgress\)"/);
  assert.match(gate, /requestEpoch\.value\+\+; busy\.value = false;/);
});
test('AUTH-03 regression: POST guarded before validation and email tokens checked before submission', async () => {
  const gate = await readFile(new URL('../src/core/auth/AuthGate.vue', import.meta.url), 'utf8');
  assert.match(gate, /async function register\(\) \{\s*if \(busy\.value \|\| oauthPending\.value\) return;/);
  assert.match(gate, /async function reset\(\) \{\s*if \(busy\.value\) return;\s*if \(!tokenPattern\.test\(verificationToken\.value\)\)/);
  assert.match(gate, /async function verify\(\) \{\s*if \(busy\.value \|\| !tokenPattern\.test\(verificationToken\.value\)\)/);
  assert.match(gate, /const epoch = requestEpoch\.value \+ 1;/);
  assert.match(gate, /if \(!mounted \|\| epoch !== requestEpoch\.value\) return;/);
});
