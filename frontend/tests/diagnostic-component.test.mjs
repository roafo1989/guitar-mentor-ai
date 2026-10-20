import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from 'vue/compiler-sfc';
import ts from 'typescript';
import { createRenderer, h, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';

// Compile and mount the REAL DiagnosticView.vue SFC without installing a browser.
// Replace ONLY service/child dependencies, not the component's own route guards.
const asModule = (code) => 'data:text/javascript,' + encodeURIComponent(code);
const vueUrl = import.meta.resolve('vue');
const blank = asModule("import { h } from '" + vueUrl + "'; export default { render() { return h('section'); } };");
const panel = asModule("import { h } from '" + vueUrl +
  "'; export default {emits:['intake-state'], setup(_,{emit}) {globalThis.__testEmit = emit; return ()=>h('section');}};");
const store = asModule("export const useLearnerStore = () => globalThis.__testLearner;");
const metronomeUi = asModule("export const closeMetronome = () => {};");
const diagnostic = asModule("export const diagnosticService = {get: async()=>({id:'session',status:'COLLECTING_EVIDENCE'}),listExercises:async()=>[],recommendations:async()=>({items:[]}),continueWithoutRecording:async()=>({id:'session',status:'READY_FOR_TASK'})};");
let cached;
async function compileRealView() {
  if (cached) return cached;
  const filename = new URL('../src/entities/diagnostic/DiagnosticView.vue', import.meta.url);
  const { descriptor, errors } = parse(await readFile(filename, 'utf8'), { filename: 'DiagnosticView.vue' });
  assert.equal(errors.length, 0);
  const script = compileScript(descriptor, { id: 'diagnostic-68', inlineTemplate: true });
  const js = ts.transpileModule(script.content, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText;
  const mapped = js.replace(/from (['"])(.*?)\1/g, (_full, _quote, name) => {
    const sources = {
      vue: vueUrl,
      'vue-router': import.meta.resolve('vue-router'),
      'vue-i18n': import.meta.resolve('vue-i18n'),
      './intake-navigation': new URL('../src/entities/diagnostic/intake-navigation.ts', import.meta.url).href,
      '../metronome/metronome-ui': metronomeUi,
    };
    const target = sources[name] ?? (
      name.endsWith('DiagnosticEvidencePanel.vue') ? panel :
      name.endsWith('.vue') ? blank :
      name.endsWith('learner.store') ? store :
      name.endsWith('diagnostic.service') ? diagnostic : null
    );
    if (!target) throw Error('Unexpected DiagnosticView runtime dependency: ' + name);
    return 'from ' + JSON.stringify(target);
  });
  cached = (await import(asModule(mapped))).default;
  return cached;
}

function headlessRenderer() {
  function node(tag, text = '') { return { tag, text, children: [], parent: null, props: {} }; }
  const remove = (n) => {
    const siblings = n.parent?.children;
    if (!siblings) return;
    const index = siblings.indexOf(n);
    if (index >= 0) siblings.splice(index, 1);
    n.parent = null;
  };
  return createRenderer({
    createElement: (tag) => node(tag),
    createText: (value) => node('#text', value),
    createComment: (value) => node('#comment', value),
    setText: (n, value) => { n.text = value; },
    setElementText: (n, value) => { n.text = value; n.children = []; },
    patchProp: (n, key, _previous, next) => { n.props[key] = next; },
    insert: (n, parent, anchor = null) => {
      if (n.parent) remove(n);
      n.parent = parent;
      const index = parent.children.indexOf(anchor);
      if (index < 0) parent.children.push(n);
      else parent.children.splice(index, 0, n);
    },
    remove,
    parentNode: (n) => n.parent,
    nextSibling: (n) => {
      if (!n.parent) return null;
      return n.parent.children[n.parent.children.indexOf(n) + 1] ?? null;
    },
  });
}

test('real mounted DiagnosticView guards router leave, confirms drafts, and handles F5', async () => {
  const oldWindow = globalThis.window;
  const listeners = new Map();
  const replies = [];
  globalThis.window = {
    localStorage: { getItem: () => 'session', setItem() {}, removeItem() {} },
    addEventListener: (name, listener) => listeners.set(name, listener),
    removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); },
    confirm: () => replies.shift() ?? false,
  };
  globalThis.__testLearner = { learner: { id: 'learner', displayName: 'Test' }, restore: async () => {} };
  globalThis.__testEmit = null;
  let app;
  try {
    const DiagnosticView = await compileRealView();
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/diagnostics', component: DiagnosticView },
        { path: '/profile', component: { render: () => h('section') } },
      ],
    });
    await router.push('/diagnostics');
    await router.isReady();
    app = headlessRenderer().createApp({ render: () => h(RouterView) });
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }));
    app.use(router);
    const root = { tag: 'root', children: [], parent: null };
    app.mount(root);
    const renderedText = (node) => [node.text ?? '', ...(node.children ?? []).map(renderedText)].join(' ');
    for (let i = 0; i < 20 && !globalThis.__testEmit; i++) {
      await Promise.resolve();
      await nextTick();
    }
    assert.equal(typeof globalThis.__testEmit, 'function', 'real view mounted evidence panel');

    globalThis.__testEmit('intake-state', { busy: true, dirty: true });
    await nextTick();
    await router.push('/profile');
    assert.equal(router.currentRoute.value.path, '/diagnostics', 'busy upload blocks router leaving');
    assert.ok(renderedText(root).includes('evidence.waitBeforeLeave'),
      'blocked navigation displays a warning');

    const unload = listeners.get('beforeunload');
    assert.equal(typeof unload, 'function');
    const event = { prevented: false, preventDefault() { this.prevented = true; }, returnValue: null };
    unload(event);
    assert.equal(event.prevented, true);

    globalThis.__testEmit('intake-state', { busy: false, dirty: true });
    await nextTick();
    assert.equal(renderedText(root).includes('evidence.waitBeforeLeave'), false,
      'P3 #101: stale busy warning clears as soon as recording/upload ends');
    replies.push(false, true);
    await router.push('/profile');
    assert.equal(router.currentRoute.value.path, '/diagnostics', 'declined confirmation keeps draft');
    await router.push('/profile');
    assert.equal(router.currentRoute.value.path, '/profile', 'confirmed discard navigates');
  } finally {
    app?.unmount();
    globalThis.window = oldWindow;
    delete globalThis.__testLearner;
    delete globalThis.__testEmit;
  }
});

test('intake copy in both languages describes existing uploads and continuing to studies', async () => {
  for (const locale of ['ru', 'en']) {
    const path = new URL('../src/i18n/' + locale + '.json', import.meta.url);
    const messages = JSON.parse(await readFile(path, 'utf8'));
    const strings = [
      messages.diagnostic.startDescription,
      messages.diagnostic.intakeDescription,
      messages.diagnostic.noFilesYet,
    ].join(' ');
    assert.doesNotMatch(strings, /ещё не реализован|будущем здесь появится|not implemented|comes later/i);
    assert.doesNotMatch(messages.diagnostic.continue,
      /без записи|without recording/i, 'button must work for a learner who already uploaded');
    assert.match(messages.diagnostic.continue,
      locale === 'ru' ? /этюд/ : /study/, 'button should describe its destination');
  }
});
