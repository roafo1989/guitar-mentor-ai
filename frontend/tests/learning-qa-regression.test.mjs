import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';

const moduleUrl = code => 'data:text/javascript,' + encodeURIComponent(code);
const blank = moduleUrl("export default { render() { return null; } };");
const service = moduleUrl(`export const attemptService = {
  capabilities: (...args) => globalThis.__qaAttempt.capabilities(...args),
  list: (...args) => globalThis.__qaAttempt.list(...args),
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

test('QA19-01 delayed old history cannot overwrite new task context', async () => {
  const old = deferred();
  const calls = [];
  globalThis.__qaAttempt = {
    capabilities: async () => ({ enabled: true, maxBytes: 1000 }),
    list: (learner, session, task) => {
      calls.push(task);
      return task === 'old' ? old.promise : Promise.resolve([{ id: 'new-only', status: 'PROCESSED' }]);
    },
    upload: async () => ({})
  };
  const { app, state } = await mount('SubmissionPanel.vue', {
    learnerId: 'student', sessionId: 'session', taskId: 'old'
  });
  try {
    app._instance.props.taskId = 'new';
    await flush();
    assert.deepEqual(state.attempts.map(x => x.id), ['new-only']);
    old.resolve([{ id: 'old-only', status: 'PROCESSED' }]);
    await flush();
    assert.deepEqual(state.attempts.map(x => x.id), ['new-only']);
    assert.ok(calls.includes('old') && calls.includes('new'));
  } finally { app.unmount(); delete globalThis.__qaAttempt; }
});

test('QA19-02 optional evidence failure does not block learning state and actions', async () => {
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 'task', taskState: 'ACTIVE', version: 0,
      availableActions: ['COMPLETE'], latestAttemptId: 'attempt', assessmentState: 'NO_ASSESSMENT',
      latestAssessmentId: null, latestDecision: null, nextStepAvailability: 'NOT_IMPLEMENTED' }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => { throw new Error('unavailable'); },
    assessments: async () => ({ items: [], nextCursor: null }),
    decide: async () => ({})
  };
  const { app, state } = await mount('LearningResultsPanel.vue', {
    learnerId: 'learner', sessionId: 'session', taskId: 'task',
    attempts: [{ id: 'attempt', status: 'PROCESSED' }], draftBusy: false
  });
  try {
    await flush();
    assert.equal(state.state.taskState, 'ACTIVE');
    assert.equal(state.canDecide, true, 'evidence error must not block COMPLETE');
    assert.equal(state.evidenceLoading, false);
    assert.ok(state.evidenceError, 'evidence panel receives isolated error');
  } finally { app.unmount(); delete globalThis.__qaLearning; }
});

test('optional decision history failure also preserves core actions', async () => {
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 'task', taskState: 'ACTIVE', version: 0,
      availableActions: ['COMPLETE'], latestAttemptId: 'attempt', assessmentState: 'NO_ASSESSMENT',
      latestAssessmentId: null, latestDecision: null, nextStepAvailability: 'NOT_IMPLEMENTED' }),
    decisions: async () => { throw new Error('decision history unavailable'); },
    evidence: async () => ({ items: [], nextCursor: null }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decide: async () => ({})
  };
  const { app, state } = await mount('LearningResultsPanel.vue', {
    learnerId: 'learner', sessionId: 'session', taskId: 'task',
    attempts: [{ id: 'attempt', status: 'PROCESSED' }], draftBusy: false
  });
  try {
    await flush();
    assert.equal(state.state.taskState, 'ACTIVE');
    assert.equal(state.canDecide, true);
    assert.ok(state.decisionHistoryError);
    assert.equal(state.decisionHistoryLoading, false);
  } finally { app.unmount(); delete globalThis.__qaLearning; }
});

test('learner can complete without a graded assessment; terminal state is server-owned', async () => {
  const previousWindow = globalThis.window;
  globalThis.window = { confirm: () => true };
  const commands = [];
  let terminal = false;
  globalThis.__qaLearning = {
    state: async () => ({
      taskId: 'task', taskState: terminal ? 'COMPLETED_BY_LEARNER' : 'ACTIVE',
      version: terminal ? 1 : 0, availableActions: terminal ? [] : ['COMPLETE'],
      latestAttemptId: 'attempt', assessmentState: 'NO_ASSESSMENT',
      latestAssessmentId: null, latestDecision: null, nextStepAvailability: 'NOT_IMPLEMENTED'
    }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decide: async (...args) => { commands.push(args[3]); terminal = true; return {}; }
  };
  const { app, state } = await mount('LearningResultsPanel.vue', {
    learnerId: 'learner', sessionId: 'session', taskId: 'task',
    attempts: [{ id: 'attempt', status: 'PROCESSED' }], draftBusy: false
  });
  try {
    await flush();
    assert.equal(state.canDecide, true);
    await state.decide('COMPLETE');
    await flush();
    assert.equal(commands.length, 1);
    assert.equal(commands[0].attemptId, 'attempt');
    assert.equal(commands[0].action, 'COMPLETE');
    assert.equal(commands[0].expectedVersion, 0);
    assert.equal(commands[0].baseAssessmentId, null);
    assert.equal(state.state.taskState, 'COMPLETED_BY_LEARNER');
    assert.equal(state.canDecide, false);
  } finally {
    app.unmount();
    globalThis.window = previousWindow;
    delete globalThis.__qaLearning;
  }
});

test('uncertain POST result retains identical idempotency key and payload on retry', async () => {
  const commands = [];
  let attempts = 0;
  globalThis.__qaLearning = {
    state: async () => ({
      taskId: 'task', taskState: 'ACTIVE', version: 3,
      availableActions: ['CONTINUE_PRACTICING'], latestAttemptId: 'attempt',
      assessmentState: 'NO_ASSESSMENT', latestAssessmentId: null,
      latestDecision: null, nextStepAvailability: 'NOT_IMPLEMENTED'
    }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async () => ({ items: [], nextCursor: null }),
    assessments: async () => ({ items: [], nextCursor: null }),
    decide: async (_l, _s, _t, command) => {
      commands.push(structuredClone(command));
      attempts++;
      if (attempts === 1) throw Error('timeout');
      return {};
    }
  };
  const { app, state } = await mount('LearningResultsPanel.vue', {
    learnerId: 'learner', sessionId: 'session', taskId: 'task',
    attempts: [{ id: 'attempt', status: 'PROCESSED' }], draftBusy: false
  });
  try {
    await flush();
    await state.decide('CONTINUE_PRACTICING');
    await flush();
    assert.equal(state.canDecide, false, 'must not allow second command after timeout');
    assert.equal(state.pending.requestId, commands[0].requestId);
    await state.retry();
    await flush();
    assert.equal(commands.length, 2);
    assert.deepEqual(commands[1], commands[0], 'retry preserves full POST payload');
    assert.equal(state.pending, null);
  } finally { app.unmount(); delete globalThis.__qaLearning; }
});

test('evidence pagination failure is local and releases its spinner', async () => {
  globalThis.__qaLearning = {
    state: async () => ({ taskId: 'task', taskState: 'ACTIVE', version: 0,
      availableActions: ['COMPLETE'], latestAttemptId: 'attempt',
      assessmentState: 'NO_ASSESSMENT', latestAssessmentId: null,
      latestDecision: null, nextStepAvailability: 'NOT_IMPLEMENTED' }),
    decisions: async () => ({ items: [], nextCursor: null }),
    evidence: async (_learner, _session, _task, cursor) => {
      if (cursor) throw new Error('next page unavailable');
      return { items: [], nextCursor: 'page-2' };
    },
    assessments: async () => ({ items: [], nextCursor: null }),
    decide: async () => ({})
  };
  const { app, state } = await mount('LearningResultsPanel.vue', {
    learnerId: 'learner', sessionId: 'session', taskId: 'task',
    attempts: [{ id: 'attempt', status: 'PROCESSED' }], draftBusy: false
  });
  try {
    await flush();
    assert.equal(state.evidenceCursor, 'page-2');
    await state.moreEvidence();
    await flush();
    assert.equal(state.evidenceLoading, false);
    assert.equal(state.loading, false);
    assert.equal(state.canDecide, true);
    assert.ok(state.evidenceError);
    assert.equal(state.error, '');
  } finally { app.unmount(); delete globalThis.__qaLearning; }
});
