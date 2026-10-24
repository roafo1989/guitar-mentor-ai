import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';

const moduleUrl = code => 'data:text/javascript,' + encodeURIComponent(code);
const blank = moduleUrl('import { h } from ' + JSON.stringify(import.meta.resolve('vue')) + '; export default { render() { return h("span"); } };');
const service = moduleUrl(`export const attemptService = {
  capabilities: (...args) => globalThis.__qaAttempt.capabilities(...args),
  page: (...args) => globalThis.__qaAttempt.page(...args),
  detail: (...args) => globalThis.__qaAttempt.detail(...args),
  upload: (...args) => globalThis.__qaAttempt.upload(...args)
};
export class AttemptHttpError extends Error {}`);
const learningService = moduleUrl(`export class LearningHttpError extends Error {}
export const learningService = {
  state: (...args) => globalThis.__qaLearning.state(...args),
  decisions: (...args) => globalThis.__qaLearning.decisions(...args),
  evidence: (...args) => globalThis.__qaLearning.evidence(...args),
  assessments: (...args) => globalThis.__qaLearning.assessments(...args),
  decide: (...args) => globalThis.__qaLearning.decide(...args)
};`);
const metronomeUi = moduleUrl('export const openExerciseMetronome = () => true; export const metronomeUi = {sourceExercise:null};');
const audioMetronome = moduleUrl('export const metronome = {getState:()=>({status:"ready"}),subscribe:(fn)=>{fn({status:"ready"});return ()=>{};},stop:()=>{},setBpm:()=>true,start:async()=>true};');
const components = new Map();
async function component(filename) {
  if (components.has(filename)) return components.get(filename);
  const source = await readFile(new URL('../src/entities/attempt/' + filename, import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename });
  assert.deepEqual(errors, []);
  const script = compileScript(descriptor, { id: filename, inlineTemplate: false });
  const js = ts.transpileModule(script.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_full, _quote, name) => {
    const value = name === 'vue' ? import.meta.resolve('vue') :
      name === 'vue-i18n' ? import.meta.resolve('vue-i18n') :
      name.endsWith('attempt.service') ? service :
      name.endsWith('learning.service') ? learningService :
      name.endsWith('metronome-ui') ? metronomeUi :
      name.endsWith('/audio/metronome') ? audioMetronome :
      name.endsWith('.vue') ? blank : null;
    if (!value) throw Error('Unmocked dependency ' + name);
    return 'from ' + JSON.stringify(value);
  });
  const target = (await import(moduleUrl(mapped))).default;
  target.render = () => h('section');
  components.set(filename, target);
  return target;
}
function renderer() {
  const node = tag => ({ tag, parent: null, children: [] });
  return createRenderer({
    createElement: node, createText: () => node('#text'),
    createComment: () => node('#comment'), setText() {}, setElementText() {},
    patchProp() {}, parentNode: n => n.parent, nextSibling: () => null,
    insert(n, p) { n.parent = p; p.children.push(n); },
    remove(n) { if (n.parent) n.parent.children = n.parent.children.filter(x => x !== n); }
  });
}
async function mount(filename, props) {
  const view = await component(filename);
  const app = renderer().createApp(view, props);
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }));
  app.mount({ tag: 'root', children: [] });
  await nextTick();
  return { app, state: app._instance.setupState };
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
}
async function flush() {
  for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick(); }
}


const attempt = n => ({
  id: 'id-' + n, number: n, status: 'PROCESSED', submittedAt: '2026-10-24T00:00:00Z',
  format: 'wav', bytes: 42, comment: null, mediaState: 'AVAILABLE',
  assessmentAvailable: n === 2, processingErrorCode: null
});
function historyFixture(options = {}) {
  const calls = [];
  const all = Array.from({ length: 45 }, (_, i) => attempt(45 - i));
  globalThis.__qaAttempt = {
    capabilities: async () => ({ enabled: true, maxBytes: 10000000, formats: ['wav'] }),
    page: async (_learner, _session, _task, cursor) => {
      calls.push(cursor);
      if (options.failCursor === cursor && cursor !== undefined) throw Error('offline');
      const rows = all.filter(item => cursor === undefined || item.number < cursor).slice(0, 20);
      return { items: rows, hasMore: all.some(a => a.number < (rows.at(-1)?.number ?? 0)),
        nextBeforeNumber: rows.at(-1)?.number ?? null };
    },
    detail: async (_l, _s, _t, id) => {
      if (options.missingId === id) throw Error('404');
      return all.find(a => a.id === id);
    },
    upload: async () => attempt(46)
  };
  return calls;
}
test('CORE-U1: 45 items navigate 20/20/5 without unbounded list API', async () => {
  const calls = historyFixture();
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    assert.equal(state.attempts.length, 20);
    assert.deepEqual(state.attempts.map(a => a.number), Array.from({ length: 20 }, (_, i) => 45-i));
    await state.navigate(26, 'older'); await flush();
    assert.equal(state.attempts.length, 20);
    await state.navigate(6, 'older'); await flush();
    assert.deepEqual(state.attempts.map(a => a.number), [5,4,3,2,1]);
    await state.navigate(26, 'newer'); await flush();
    assert.equal(state.attempts[0].number, 25);
    await state.navigate(undefined, 'latest'); await flush();
    assert.equal(state.attempts[0].number, 45);
    assert.deepEqual(calls, [undefined,26,6,26,undefined]);
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U2: failed older page retains previous rows/cursor and reports stale', async () => {
  historyFixture({ failCursor: 26 });
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    await state.navigate(26, 'older'); await flush();
    assert.equal(state.attempts[0].number, 45);
    assert.equal(state.pageCursor, undefined);
    assert.equal(state.historyStale, true);
    assert.ok(state.historyError);
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U3: selection survives page change and details are bounded', async () => {
  historyFixture();
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    state.selectedId = 'id-2';
    await flush();
    assert.equal(state.pinned.some(a => a.id === 'id-2'), true);
    await state.navigate(26, 'older'); await flush();
    assert.equal(state.selectedId, 'id-2');
    assert.ok(state.displayAttempts.length <= 22);
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U4: unavailable selected detail prevents completion, never changes selection', async () => {
  historyFixture({ missingId: 'old-id' });
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 't', taskState: 'ACTIVE', version: 2,
      availableActions: ['COMPLETE'], latestAttemptId: 'id-45', assessmentState: 'NO_ASSESSMENT' }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    decide: async () => ({})
  };
  const { app, state } = await mount('LearningResultsPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't', attempts: [attempt(45)], draftBusy: false });
  try {
    await flush();
    await state.chooseAttempt('old-id'); await flush();
    assert.equal(state.selected, 'old-id');
    assert.equal(state.canDecide, false);
    assert.ok(state.detailError);
  } finally { app.unmount(); delete globalThis.__qaLearning; delete globalThis.__qaAttempt; }
});

test('CORE-U5: successful upload queues Latest while page GET is pending; POST exactly once', async () => {
  const calls = historyFixture();
  const slow = deferred();
  let reads = 0, posts = 0;
  const originalPage = globalThis.__qaAttempt.page;
  globalThis.__qaAttempt.page = (...args) => {
    reads++;
    return reads === 2 ? slow.promise : originalPage(...args);
  };
  globalThis.__qaAttempt.upload = async () => { posts++; return attempt(46); };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    const reading = state.readPage();
    await flush();
    state.file = new File(['sound'], 'example.wav');
    await flush();
    const submitting = state.submit();
    await flush();
    assert.equal(posts, 1);
    slow.resolve({ items: [attempt(45)], hasMore: false, nextBeforeNumber: null });
    await Promise.all([reading, submitting]);
    await flush();
    assert.equal(posts, 1);
    assert.ok(calls.length >= 2, 'successful upload triggers a new first-page GET');
    assert.ok(state.notice, 'upload success survives history refresh');
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U6: upload 200 followed by history 500 preserves success without duplicate POST', async () => {
  historyFixture();
  let writes = 0;
  globalThis.__qaAttempt.upload = async () => { writes++; return attempt(46); };
  const original = globalThis.__qaAttempt.page;
  let reads = 0;
  globalThis.__qaAttempt.page = (...args) => {
    reads++;
    if (reads > 1) throw new Error('server 500');
    return original(...args);
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    state.file = new File(['sound'], 'example.wav');
    await flush();
    await state.submit();
    assert.equal(writes, 1);
    assert.equal(state.file, null);
    assert.ok(state.notice);
    assert.ok(state.historyError);
    await state.requestRefresh();
    assert.equal(writes, 1);
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U7: unavailable selected page DTO never bypasses detail GET', async () => {
  historyFixture({ missingId: 'id-45' });
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 't', taskState: 'ACTIVE', version: 2,
      availableActions: ['COMPLETE'], latestAttemptId: 'id-45', assessmentState: 'NO_ASSESSMENT' }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    decide: async () => { throw Error('must not POST'); }
  };
  const { app, state } = await mount('LearningResultsPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't', attempts: [attempt(45)],
      selectedId: 'id-45', latestId: 'id-45', draftBusy: false });
  try {
    await flush();
    assert.equal(state.selected, 'id-45');
    assert.equal(state.canDecide, false);
    assert.ok(state.detailError);
  } finally { app.unmount(); delete globalThis.__qaLearning; delete globalThis.__qaAttempt; }
});

test('CORE-U8: hidden tab suppresses processing poll, visible resumes a single read', async () => {
  const previous = globalThis.document;
  const callbacks = new Map();
  globalThis.document = {
    hidden: true,
    addEventListener: (name, callback) => callbacks.set(name, callback),
    removeEventListener: name => callbacks.delete(name)
  };
  const calls = historyFixture();
  const original = globalThis.__qaAttempt.page;
  globalThis.__qaAttempt.page = async (...args) => {
    const page = await original(...args);
    return { ...page, items: page.items.map((item, i) =>
      i === 0 ? { ...item, status: 'PROCESSING' } : item) };
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    const initial = calls.length;
    await state.pollHistory();
    assert.equal(calls.length, initial);
    globalThis.document.hidden = false;
    callbacks.get('visibilitychange')();
    await flush();
    assert.equal(calls.length, initial + 1);
  } finally {
    app.unmount();
    globalThis.document = previous;
    delete globalThis.__qaAttempt;
  }
});
test('CORE-U9: pending decision preserves selected id, reason and idempotency across attempts changes', async () => {
  historyFixture();
  const stalled = deferred();
  let calls = 0;
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 't', taskState: 'ACTIVE', version: 2,
      availableActions: ['CONTINUE_PRACTICING'], latestAttemptId: 'id-45',
      assessmentState: 'NO_ASSESSMENT' }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    decide: async () => { calls++; return stalled.promise; }
  };
  const { app, state } = await mount('LearningResultsPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't', attempts: [attempt(45)],
      selectedId: 'id-45', latestId: 'id-45', draftBusy: false });
  try {
    await flush();
    assert.equal(state.canDecide, true);
    state.reason = 'keep practicing';
    const sending = state.decide('CONTINUE_PRACTICING');
    await flush();
    assert.ok(state.pending);
    const originalRequestId = state.pending.requestId;
    await state.chooseAttempt('id-44');
    assert.equal(state.selected, 'id-45');
    assert.equal(state.reason, 'keep practicing');
    assert.equal(state.pending.requestId, originalRequestId);
    stalled.resolve({});
    await sending;
    await flush();
    assert.equal(calls, 1);
  } finally { app.unmount(); delete globalThis.__qaLearning; delete globalThis.__qaAttempt; }
});
test('CORE-U10: template retains keyboard-native history controls and accessible error regions', async () => {
  const source = await readFile(new URL('../src/entities/attempt/SubmissionPanel.vue', import.meta.url), 'utf8');
  const learning = await readFile(new URL('../src/entities/attempt/LearningResultsPanel.vue', import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename: 'SubmissionPanel.vue' });
  assert.deepEqual(errors, []);
  assert.match(descriptor.template.content, /<nav class="form-actions" :aria-label="t\('attempt.history'\)">/);
  for (const action of ['older', 'newer', 'latest', 'refresh']) {
    assert.ok(descriptor.template.content.includes("{{ t('attempt." + action + "') }}"), action + " has a translated label");
  }
  assert.match(descriptor.template.content, /role="alert"/);
  assert.match(descriptor.template.content, /role="status"/);
  assert.match(learning, /<select id="learning-attempt"/);
  assert.match(learning, /<label for="learning-attempt"/);
});


test('CORE-U12: polling waits for at most two pinned detail GETs, without overlapping cycles', async () => {
  historyFixture();
  const first = deferred(), second = deferred();
  const original = globalThis.__qaAttempt.detail;
  const requests = [];
  globalThis.__qaAttempt.detail = (...args) => {
    requests.push(args[3]);
    return args[3] === 'id-2' ? first.promise : args[3] === 'id-1' ? second.promise : original(...args);
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    state.selectedId = 'id-2';
    state.latestId = 'id-1';
    await flush();
    const pending = state.readPage();
    await flush();
    const pageCalls = [];
    const oldPage = globalThis.__qaAttempt.page;
    globalThis.__qaAttempt.page = (...args) => { pageCalls.push(args); return oldPage(...args); };
    await state.pollHistory();
    assert.equal(pageCalls.length, 0, 'next poll cannot overlap deferred pinned details');
    assert.deepEqual([...new Set(requests)].sort(), ['id-1', 'id-2']);
    first.resolve(attempt(2));
    second.resolve(attempt(1));
    await pending; await flush();
    assert.ok(state.displayAttempts.length <= 22);
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U13: selected off-page remains displayed if pinned GET later fails', async () => {
  historyFixture();
  let fails = false;
  const original = globalThis.__qaAttempt.detail;
  globalThis.__qaAttempt.detail = (...args) => fails ? Promise.reject(Error('offline')) : original(...args);
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    state.selectedId = 'id-2';
    await flush();
    assert.ok(state.displayAttempts.some(x => x.id === 'id-2'));
    fails = true;
    await state.readPage(); await flush();
    assert.equal(state.selectedId, 'id-2');
    assert.ok(state.displayAttempts.some(x => x.id === 'id-2'), 'stale pinned option survives');
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U14: selected detail outside page is coalesced with parent pinned fetch', async () => {
  historyFixture();
  const original = globalThis.__qaAttempt.detail;
  const delayed = deferred();
  let count = 0;
  globalThis.__qaAttempt.detail = (...args) => {
    if (args[3] === 'id-2') { count++; return delayed.promise; }
    return original(...args);
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    state.selectedId = 'id-2'; await flush();
    const pending = state.fetchDetail('id-2');
    assert.equal(count, 1, 'selected detail and pinned detail share pending GET');
    delayed.resolve(attempt(2));
    const detail = await pending;
    assert.equal(detail.id, 'id-2');
  } finally { delayed.resolve(attempt(2)); app.unmount(); delete globalThis.__qaAttempt; }
});

test('CORE-U15: failed detail is evicted and Retry performs a fresh network GET', async () => {
  historyFixture();
  let reads = 0;
  const original = globalThis.__qaAttempt.detail;
  globalThis.__qaAttempt.detail = (...args) => {
    reads++;
    return reads === 1 ? Promise.reject(Error('temporary outage')) : original(...args);
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    await assert.rejects(state.fetchDetail('id-2'));
    assert.equal(reads, 1);
    const restored = await state.fetchDetail('id-2');
    assert.equal(restored.id, 'id-2');
    assert.equal(reads, 2, 'Retry must issue a new GET, not reuse rejected Promise');
    await state.fetchDetail('id-2');
    assert.equal(reads, 3, 'fulfilled detail is not cached after completion');
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});
test('CORE-U16: old-context completion cannot unblock polling while new detail is in flight', async () => {
  historyFixture();
  const oldDetail = deferred(), newDetail = deferred();
  const originalDetail = globalThis.__qaAttempt.detail;
  globalThis.__qaAttempt.detail = (_l, _s, task, id) => {
    if (id === 'old-id') return oldDetail.promise;
    if (id === 'new-id') return newDetail.promise;
    return originalDetail(_l, _s, task, id);
  };
  const originalPage = globalThis.__qaAttempt.page;
  let pageReads = 0;
  globalThis.__qaAttempt.page = (...args) => {
    pageReads++;
    return originalPage(...args).then(page => ({
      ...page, items: page.items.map((a, i) => i === 0 ? { ...a, status: 'PROCESSING' } : a)
    }));
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 'old-task' });
  try {
    await flush();
    const stale = state.fetchDetail('old-id');
    app._instance.props.taskId = 'new-task';
    await flush();
    const current = state.fetchDetail('new-id');
    oldDetail.resolve(attempt(2));
    await stale; await flush();
    const readsBefore = pageReads;
    await state.pollHistory();
    assert.equal(pageReads, readsBefore, 'new context remains blocked while new detail is pending');
    newDetail.resolve(attempt(1));
    await current; await flush();
    await state.pollHistory();
    assert.equal(pageReads, readsBefore + 1, 'new context resumes after its own request settles');
  } finally {
    oldDetail.resolve(attempt(2));
    newDetail.resolve(attempt(1));
    app.unmount();
    delete globalThis.__qaAttempt;
  }
});

test('CORE-U17: retry selected detail recovers guarded decision after transient error', async () => {
  historyFixture();
  let detailCalls = 0;
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 't', taskState: 'ACTIVE', version: 2,
      availableActions: ['CONTINUE_PRACTICING'], latestAttemptId: 'id-45',
      assessmentState: 'NO_ASSESSMENT' }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    decide: async () => ({})
  };
  const loader = async id => {
    detailCalls++;
    if (detailCalls === 1) throw Error('offline');
    return attempt(Number(id.slice(3)));
  };
  const { app, state } = await mount('LearningResultsPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't', attempts: [attempt(45)],
      selectedId: 'id-45', latestId: 'id-45', draftBusy: false,
      loadAttemptDetail: loader });
  try {
    await flush();
    assert.equal(state.canDecide, false);
    assert.ok(state.detailError);
    await state.loadDetail(1);
    await flush();
    assert.equal(detailCalls, 2);
    assert.equal(state.detailError, '');
    assert.equal(state.canDecide, true);
  } finally { app.unmount(); delete globalThis.__qaLearning; delete globalThis.__qaAttempt; }
});

test('QA-126-B01: explicit results Refresh fetches updated detail, not a fulfilled cached Promise', async () => {
  historyFixture();
  const { app: parent, state: parentState } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  let version = 1, detailCalls = 0;
  const original = globalThis.__qaAttempt.detail;
  globalThis.__qaAttempt.detail = async (...args) => {
    detailCalls++;
    return { ...await original(...args), number: version };
  };
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 't', taskState: 'ACTIVE', version: 3,
      availableActions: ['CONTINUE_PRACTICING'], latestAttemptId: 'id-45',
      assessmentState: 'NO_ASSESSMENT' }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    decide: async () => ({})
  };
  const { app: child, state: childState } = await mount('LearningResultsPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't', attempts: [attempt(45)],
      selectedId: 'id-45', latestId: 'id-45', draftBusy: false,
      loadAttemptDetail: parentState.fetchDetail });
  try {
    await flush();
    assert.equal(childState.currentAttempt.number, 1);
    const first = detailCalls;
    version = 2;
    await childState.refresh(); await flush();
    assert.equal(detailCalls, first + 1, 'manual Refresh must issue a fresh GET');
    assert.equal(childState.currentAttempt.number, 2);
    assert.equal(childState.canDecide, true);
  } finally {
    child.unmount(); parent.unmount();
    delete globalThis.__qaLearning; delete globalThis.__qaAttempt;
  }
});
test('QA-126-B02: pending selected GET remains shared through page refresh', async () => {
  historyFixture();
  const pendingDetail = deferred();
  const original = globalThis.__qaAttempt.detail;
  let calls = 0;
  globalThis.__qaAttempt.detail = (...args) => {
    if (args[3] === 'id-2') { calls++; return pendingDetail.promise; }
    return original(...args);
  };
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'l', sessionId: 's', taskId: 't' });
  try {
    await flush();
    state.selectedId = 'id-2';
    await flush();
    const selectedGet = state.fetchDetail('id-2');
    const pageReload = state.readPage();
    await flush();
    assert.equal(calls, 1, 'page reload cannot start a duplicate selected detail GET');
    const childSameId = state.fetchDetail('id-2');
    assert.equal(calls, 1, 'in-flight GET is shared by child and pinned refresh');
    pendingDetail.resolve(attempt(2));
    await Promise.all([pageReload, selectedGet, childSameId]);
    assert.ok(state.displayAttempts.some(a => a.id === 'id-2'));
  } finally {
    pendingDetail.resolve(attempt(2)); app.unmount(); delete globalThis.__qaAttempt;
  }
});

test('QA-126-POLL: disabled navigation throughout deferred processing poll, then reenabled', async () => {
  historyFixture();
  const waiting = deferred();
  const original = globalThis.__qaAttempt.page;
  let pageCalls = 0;
  globalThis.__qaAttempt.page = (...args) => {
    pageCalls++;
    return pageCalls === 2 ? waiting.promise : original(...args).then(p => ({
      ...p, items: p.items.map((a,i) => i === 0 ? {...a,status:'PROCESSING'} : a)
    }));
  };
  const {app,state} = await mount('SubmissionPanel.vue',{learnerId:'l',sessionId:'s',taskId:'t'});
  try {
    await flush();
    assert.equal(state.pageBusy,false);
    const polling = state.pollHistory(); await flush();
    assert.equal(state.pageBusy,true,'nav buttons bound to pageBusy must disable during poll');
    const callsBefore = pageCalls;
    await state.navigate(undefined,'older');
    assert.equal(pageCalls,callsBefore);
    waiting.resolve({items:[{...attempt(45),status:'PROCESSING'}],hasMore:false,nextBeforeNumber:null});
    await polling; await flush();
    assert.equal(state.pageBusy,false,'nav controls reenable after poll');
  } finally {waiting.resolve({items:[],hasMore:false,nextBeforeNumber:null});app.unmount();delete globalThis.__qaAttempt;}
});

test('#150 real setup state: audio tabs retain draft/comment and lock while capturing or uploading', async () => {
  historyFixture();
  const { app, state } = await mount('SubmissionPanel.vue',
    { learnerId: 'learner', sessionId: 'session', taskId: 'task' });
  try {
    await flush();
    assert.equal(state.inputMode, 'record');
    state.comment = 'My practice notes';
    state.file = {name:'take.webm',size:128};
    state.sourceKind = 'record';
    state.changeInputMode('file');
    assert.equal(state.inputMode, 'file');
    assert.equal(state.file.name, 'take.webm');
    assert.equal(state.comment, 'My practice notes');

    state.recordingActive = true;
    state.changeInputMode('record');
    assert.equal(state.inputMode, 'file', 'cannot switch while recording');
    state.recordingActive = false;
    state.uploading = true;
    state.changeInputMode('record');
    assert.equal(state.inputMode, 'file', 'cannot switch while uploading');
    state.uploading = false;

    state.changeInputMode('record');
    assert.equal(state.inputMode, 'record');
    assert.equal(state.file.name, 'take.webm', 'mode switch never deletes an unsent take');
    state.removeDraft();
    assert.equal(state.file, null);
    assert.equal(state.sourceKind, null);
    assert.equal(state.comment, 'My practice notes', 'removing attachment does not erase comment');
  } finally { app.unmount(); }
});
